package io.legado.web

import com.sun.net.httpserver.HttpServer
import com.google.gson.JsonParser
import io.legado.app.data.entities.BookSource
import io.legado.app.help.engine.Engine
import io.legado.app.utils.GSON
import io.legado.web.auth.UserPrincipal
import io.legado.web.config.DbSettings
import io.legado.web.db.DatabaseFactory
import io.legado.web.service.SettingService
import io.legado.web.service.SourceService
import io.legado.web.service.UserService
import io.legado.web.service.ai.*
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AiRepairSafetyTest {
    @Test
    fun javascriptWaitsForConfirmationAndChallengesNeverReachModel() = runBlocking {
        val db = Files.createTempFile("ai-safety-", ".db").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${db.absolutePath}", "", "", 1))
        Engine.secret = "ai-safety-test"
        val hits = AtomicInteger()
        val scripts = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = when (path) {
                "/v1/chat/completions" -> {
                    hits.incrementAndGet()
                    val patch = mapOf("bookList" to "@js:java.ajax('$base/executed'); java.getElements('.book')")
                    GSON.toJson(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to GSON.toJson(patch))))))
                }
                "/executed" -> { scripts.incrementAndGet(); "ok" }
                "/blocked" -> "<title>Just a moment</title><div>Checking your browser</div>"
                "/search" -> "<div class='book'><a href='/book'>Book</a></div>"
                "/book" -> "<h1>Book</h1><a href='/toc'>toc</a>"
                "/toc" -> "<div class='chapter'><a href='/content'>Chapter</a></div>"
                else -> "<div class='content'>Chapter content with enough text.</div>"
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(if (path == "/blocked") 503 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            val settings = SettingService()
            val sources = SourceService(settings, UserService(settings))
            val models = AiModelService()
            val repair = AiRepairService(sources, models)
            val user = UserPrincipal(1, "admin", "admin")
            val source = GSON.fromJson("""{
                "bookSourceUrl":"$base", "bookSourceName":"Safety", "searchUrl":"/search?q={{key}}",
                "ruleSearch":{"bookList":".missing","name":"a@text","bookUrl":"a@href"},
                "ruleBookInfo":{"name":"h1@text","tocUrl":"a@href"},
                "ruleToc":{"chapterList":".chapter","chapterName":"a@text","chapterUrl":"a@href"},
                "ruleContent":{"content":".content@text"}
            }""", BookSource::class.java)
            val id = sources.save(user, null, source, false)
            val detectOnly = mutableListOf<com.google.gson.JsonObject>()
            repair.repair(user, RepairRequest(id, 0, "book")) { detectOnly += GSON.toJsonTree(it).asJsonObject }
            assertEquals("search", detectOnly.single { it["type"].asString == "detect-failed" }["stage"].asString)
            assertTrue(detectOnly.none { it["type"].asString == "result" })
            assertEquals(0, hits.get(), "detect-only must not call a model")
            val progress = mutableListOf<io.legado.web.service.SourceCheckService.CheckProgress>()
            var completed = 0
            io.legado.web.service.SourceCheckService(sources, settings).check(user, listOf(id), "book", { progress += it }) { done, total, result ->
                completed = done
                assertEquals(1, total)
                assertEquals("noResult", result.errorType)
            }
            assertEquals(listOf("started", "stage"), progress.map { it.type })
            assertEquals(listOf("search", "search"), progress.map { it.stage })
            assertTrue(progress.all { it.id == id && it.elapsed >= 0 })
            assertEquals(1, completed)
            val model = models.save(user, null, AiModelInput("mock", "$base/v1", "mock", "secret"))
            val request = RepairRequest(id, model.id, "book")
            val events = mutableListOf<com.google.gson.JsonObject>()
            repair.repair(user, request) { events += GSON.toJsonTree(it).asJsonObject }
            assertEquals(listOf("check:search", "model:search"), events.filter { it["type"].asString == "progress" }
                .map { it["phase"].asString + ":" + it["stage"].asString })
            assertEquals(0, scripts.get(), "unconfirmed model JavaScript executed")
            assertEquals(1, hits.get())
            assertTrue(events.none { it["type"].asString == "result" })
            val token = events.single { it["type"].asString == "awaiting-confirmation" }["confirmationToken"].asString
            events.clear()
            repair.repair(user, request.copy(confirmationToken = token)) { events += GSON.toJsonTree(it).asJsonObject }
            assertEquals(listOf("search", "bookInfo", "toc", "content"), events.filter { it["type"].asString == "progress" }
                .map { it["stage"].asString })
            assertEquals(1, scripts.get())
            assertTrue(events.any { it["type"].asString == "result" })
            assertFailsWith<io.legado.web.auth.BadRequestException> {
                repair.repair(user, request.copy(confirmationToken = token)) {}
            }
            source.searchUrl = "/blocked"
            sources.save(user, id, source, false)
            events.clear()
            repair.repair(user, request) { events += GSON.toJsonTree(it).asJsonObject }
            val blocked = events.single { it["type"].asString == "detect-failed" }
            assertEquals("blocked", blocked["errorType"].asString)
            assertEquals("needs-verification", blocked["code"].asString)
            assertEquals(1, hits.get(), "challenge must stop before requesting a model")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun wrappedNetworkFailuresStopBeforeModelButHttpErrorsRemainRepairable() = runBlocking {
        val db = Files.createTempFile("ai-network-", ".db").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${db.absolutePath}", "", "", 1))
        Engine.secret = "ai-network-test"
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { exchange ->
            val model = exchange.requestURI.path == "/v1/chat/completions"
            val body = if (model) {
                calls.incrementAndGet()
                val patch = GSON.toJson(mapOf("bookList" to "@js:result"))
                GSON.toJson(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to patch)))))
            } else "<h1>Page</h1>"
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (exchange.requestURI.path == "/missing") 404 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val settings = SettingService()
            val sources = SourceService(settings, UserService(settings))
            val models = AiModelService()
            val user = UserPrincipal(1, "admin", "admin")
            val model = models.save(user, null, AiModelInput("mock", "$base/v1", "mock", "secret"))
            val repair = AiRepairService(sources, models)
            for ((exception, type) in listOf(
                "java.net.UnknownHostException" to "dns",
                "javax.net.ssl.SSLHandshakeException" to "tls",
                "java.net.ConnectException" to "connection",
                "java.net.SocketTimeoutException" to "timeout",
            )) {
                val source = BookSource(bookSourceUrl = base, bookSourceName = type, searchUrl = "/page").apply {
                    ruleSearch = io.legado.app.data.entities.rule.SearchRule(bookList = "@js:throw new Packages.$exception('network witness')")
                }
                val id = sources.save(user, null, source, false)
                val events = mutableListOf<com.google.gson.JsonObject>()
                repair.repair(user, RepairRequest(id, model.id, "book")) { events += GSON.toJsonTree(it).asJsonObject }
                val failed = events.single { it["type"].asString == "detect-failed" }
                assertEquals(type, failed["errorType"].asString)
                assertTrue(failed["error"].asString.isNotBlank())
                assertEquals("end", events.last()["type"].asString)
                assertEquals(0, calls.get(), "$type consumed a model call")
            }
            val source = BookSource(bookSourceUrl = base, bookSourceName = "http", searchUrl = "/missing")
            val id = sources.save(user, null, source, false)
            val events = mutableListOf<com.google.gson.JsonObject>()
            repair.repair(user, RepairRequest(id, model.id, "book")) { events += GSON.toJsonTree(it).asJsonObject }
            assertEquals(1, calls.get(), "ordinary HTTP errors can be caused by rules")
            assertTrue(events.any { it["type"].asString == "awaiting-confirmation" })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun executableRuleFormsAreFlaggedIncludingRawScriptFields() {
        val source = BookSource()
        listOf(
            RepairStage.search to """{"bookList":"@webjs:document.body"}""",
            RepairStage.search to """{"name":"{{java.ajax('https://example.com')}}"}""",
            RepairStage.toc to """{"formatJs":"result"}""",
            RepairStage.content to """{"imageDecode":"result"}""",
            RepairStage.content to """{"contentBatch":"result"}""",
            RepairStage.content to """{"payAction":"result"}""",
        ).forEach { (stage, patch) ->
            assertTrue(AiRepairPayload.diff(source, AiRepairPayload.patch(stage, source, patch)).single().javascript, patch)
        }
    }
}
