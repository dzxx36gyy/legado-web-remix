package io.legado.web

import com.sun.net.httpserver.HttpServer
import io.legado.app.data.entities.HttpTTS
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.UserPrincipal
import io.legado.web.config.AppSettings
import io.legado.web.config.DbSettings
import io.legado.web.db.Books
import io.legado.web.db.ChapterContents
import io.legado.web.db.Chapters
import io.legado.web.db.DatabaseFactory
import io.legado.web.db.Users
import io.legado.web.service.*
import io.legado.web.service.tts.*
import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.gson.JsonParser
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.legado.web.auth.AUTH_JWT
import io.legado.web.routes.continuousTtsRoutes

class ContinuousSpeechTest {
    private suspend fun fixture(run: suspend (ContinuousSpeech, UserPrincipal, ContinuousRequest, HttpServer) -> Unit) {
        val dir = Files.createTempDirectory("continuous-speech-").toFile()
        val db = DbSettings("sqlite", "jdbc:sqlite:${dir.absolutePath}/t.db", "", "", 1)
        DatabaseFactory.init(db)
        val (uid, book) = transaction(DatabaseFactory.database) {
            val uid = Users.insertAndGetId { it[username] = "speaker"; it[passwordHash] = "unused"; it[role] = "admin"; it[createdAt] = 0 }.value
            val book = Books.insertAndGetId { it[userId] = uid; it[bookUrl] = "https://example.org/book"; it[origin] = "https://example.org"; it[name] = "Book"; it[createdAt] = 0 }.value
            repeat(2) { index ->
                Chapters.insert { it[bookId] = book; it[Chapters.index] = index; it[url] = "https://example.org/chapter/$index"; it[title] = "Chapter $index" }
                ChapterContents.insert { it[bookId] = book; it[ChapterContents.index] = index; it[chapterUrl] = "https://example.org/chapter/$index"; it[content] = "Paragraph $index"; it[updatedAt] = 0 }
            }
            uid to book
        }
        val settings = SettingService()
        val sources = SourceService(settings, UserService(settings))
        val tts = TtsService(sources)
        val shelf = ShelfService(sources, ReplaceRuleService(), AppSettings(dir, db, "j", "e", "i", 1, emptyList(), true))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        val user = UserPrincipal(uid, "speaker", "admin")
        val engine = tts.save(user, null, HttpTTS(name = "stream", url = "http://127.0.0.1:${server.address.port}/speech"))
        val service = ContinuousSpeech(shelf, tts, scope)
        try { run(service, user, ContinuousRequest(book, 0, listOf(SpeechPart(0, 0, "first")), engine.id), server) }
        finally { scope.cancel(); server.stop(0); dir.deleteRecursively() }
    }

    private fun frame() = ByteArray(144).apply { this[0] = 255.toByte(); this[1] = 243.toByte(); this[2] = 100; this[3] = 192.toByte() }

    @Test
    fun firstFrameArrivesBeforeProviderCompletesAndCancellationStopsFurtherOutput() = runBlocking {
        fixture { service, user, request, server ->
            val release = CountDownLatch(1)
            server.createContext("/speech") { exchange ->
                exchange.responseHeaders.set("Content-Type", "audio/mpeg")
                exchange.sendResponseHeaders(200, 0)
                try {
                    exchange.responseBody.use { output ->
                        output.write(frame()); output.flush()
                        release.await(10, TimeUnit.SECONDS)
                        output.write(frame())
                    }
                } finally { exchange.close() }
            }
            val id = service.create(user, request)
            val first = CompletableDeferred<ByteArray>()
            var count = 0
            val playback = launch(Dispatchers.IO) { service.audio(user, id) { count++; first.complete(it) } }
            try {
                assertContentEquals(frame(), withTimeout(5000) { first.await() })
                assertFalse(service.status(user, id, 0).done)
                service.stop(user, id)
                withTimeout(3000) { playback.join() }
                assertTrue(playback.isCancelled)
                assertEquals(1, count)
                assertFailsWith<NotFoundException> { service.status(user, id, 0) }
            } finally { release.countDown(); playback.cancelAndJoin() }
        }
    }

    @Test
    fun truncatedUpstreamResponseReportsConnectionInterruption() = runBlocking {
        fixture { service, user, request, server ->
            server.createContext("/speech") { exchange ->
                exchange.sendResponseHeaders(200, 288)
                try {
                    exchange.responseBody.write(frame())
                } finally { exchange.close() }
            }
            val id = service.create(user, request)
            withTimeout(5000) { service.audio(user, id) {} }
            val status = service.status(user, id, 0)
            assertTrue(status.done)
            assertEquals("upstream_disconnected", status.errorCode)
        }
    }

    @Test
    fun progressEventsAreCompleteJsonValuesOnSingleLines() = runBlocking {
        fixture { service, user, request, server ->
            server.createContext("/speech") { exchange ->
                exchange.sendResponseHeaders(200, 144)
                exchange.responseBody.use { it.write(frame()) }
            }
            val id = service.create(user, request)
            service.audio(user, id) {}
            val algorithm = Algorithm.HMAC256("continuous-test")
            val token = JWT.create().withSubject(user.id.toString()).sign(algorithm)
            testApplication {
                application {
                    install(Authentication) {
                        jwt(AUTH_JWT) {
                            verifier(JWT.require(algorithm).build())
                            validate { JWTPrincipal(it.payload) }
                        }
                    }
                    routing { continuousTtsRoutes(service) }
                }
                val events = client.get("/api/tts/continuous/$id/events") { bearerAuth(token) }
                    .bodyAsText().trim().lines().map { JsonParser.parseString(it).asJsonObject }
                assertEquals(1, events.size)
                assertTrue(events.single()["done"].asBoolean)
                assertEquals(listOf("first", "Chapter 1", "Paragraph 1"),
                    events.single()["cues"].asJsonArray.map { it.asJsonObject["part"].asJsonObject["text"].asString })
            }
        }
    }

    @Test
    fun singleAudioRequestIncludesNextChapterAndRejectsOtherUsers() = runBlocking {
        fixture { service, user, request, server ->
            server.createContext("/speech") { exchange ->
                exchange.responseHeaders.set("Content-Type", "audio/mpeg")
                exchange.sendResponseHeaders(200, 144)
                exchange.responseBody.use { it.write(frame()) }
            }
            val id = service.create(user, request)
            assertFailsWith<NotFoundException> { service.status(user.copy(id = user.id + 1), id, 0) }
            val output = java.io.ByteArrayOutputStream()
            withTimeout(5000) { service.audio(user, id) { output.write(it) } }
            val status = service.status(user, id, 0)
            assertTrue(status.done)
            assertNull(status.error)
            assertEquals(listOf(0, 1, 1), status.cues.map { it.chapter })
            assertEquals(listOf("first", "Chapter 1", "Paragraph 1"), status.cues.map { it.part.text })
            assertEquals(432, output.size())
            assertEquals(0.072, status.seconds, 0.0000001)
        }
    }
}
