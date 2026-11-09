package io.legado.web

import com.sun.net.httpserver.HttpServer
import io.legado.web.auth.ForbiddenException
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.UserPrincipal
import io.legado.web.config.DbSettings
import io.legado.web.db.DatabaseFactory
import io.legado.web.db.Settings
import io.legado.web.db.SourceSubscriptions
import io.legado.web.db.Users
import io.legado.web.db.dbQuery
import io.legado.web.routes.SubscriptionRequest
import io.legado.web.service.SettingService
import io.legado.web.service.SourceService
import io.legado.web.service.SubscriptionService
import io.legado.web.service.TtsService
import io.legado.web.service.UserService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SubscriptionOwnershipTest {
    @Test
    fun privateSubscriptionsAreManageableOnlyByTheirCreator() = runBlocking {
        val file = Files.createTempFile("subscription-owner-", ".db").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${file.absolutePath}", "", "", 1))
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/tts") { exchange ->
            hits.incrementAndGet()
            val bytes = """[{"name":"mock-engine","url":"http://127.0.0.1:1/speak"}]""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            transaction(DatabaseFactory.database) {
                Settings.insert {
                    it[Settings.key] = SettingService.ALLOW_USER_SOURCES
                    it[Settings.value] = "true"
                }
            }
            val accounts = transaction(DatabaseFactory.database) {
                listOf("owner" to "user", "other" to "user", "admin" to "admin").map { (name, role) ->
                    Users.insertAndGetId {
                        it[username] = name
                        it[passwordHash] = "unused"
                        it[Users.role] = role
                        it[createdAt] = 0
                    }.value
                }
            }
            val settings = SettingService()
            val sources = SourceService(settings, UserService(settings))
            val ttsSubscriptions = SubscriptionService(sources, TtsService(sources))
            val ruleSubscriptions = SubscriptionService(sources, ruleKind = "replace", importRules = { _, _ -> 0 })
            val sourceSubscriptions = SubscriptionService(sources)
            val owner = UserPrincipal(accounts[0], "owner", "user")
            val other = UserPrincipal(accounts[1], "other", "user")
            val admin = UserPrincipal(accounts[2], "admin", "admin")
            val link = "http://127.0.0.1:${server.address.port}/tts"
            val change = SubscriptionRequest("hijacked", link, false, true)

            val id = ttsSubscriptions.create(owner, "private", link, false, true)
            assertEquals(1, hits.get())
            assertFailsWith<ForbiddenException> { ttsSubscriptions.update(admin, id, change) }
            assertFailsWith<ForbiddenException> { ttsSubscriptions.update(other, id, change) }
            assertFailsWith<ForbiddenException> { ttsSubscriptions.delete(admin, id) }
            assertFailsWith<ForbiddenException> { ttsSubscriptions.refresh(admin, id) }
            assertFailsWith<ForbiddenException> { ttsSubscriptions.refresh(other, id) }
            assertEquals(1, hits.get())
            assertEquals(listOf("private"), ttsSubscriptions.list(owner).map { it.name })
            assertEquals(0, ttsSubscriptions.list(admin).size)
            assertEquals(0, ttsSubscriptions.list(other).size)

            ttsSubscriptions.update(owner, id, SubscriptionRequest("renamed", link, false, true))
            assertEquals("renamed", ttsSubscriptions.list(owner).single().name)
            ttsSubscriptions.refresh(owner, id)
            assertEquals(2, hits.get())

            val ruleId = dbQuery {
                SourceSubscriptions.insertAndGetId {
                    it[ownerId] = owner.id
                    it[kind] = "replace"
                    it[name] = "rule"
                    it[SourceSubscriptions.url] = link
                    it[createdAt] = 0
                }.value
            }
            assertFailsWith<ForbiddenException> { ruleSubscriptions.update(admin, ruleId, change) }
            assertFailsWith<NotFoundException> { ttsSubscriptions.delete(owner, ruleId) }
            assertEquals("rule", ruleSubscriptions.list(owner).single().name)

            val legacyPublic = dbQuery {
                SourceSubscriptions.insertAndGetId {
                    it[ownerId] = owner.id
                    it[kind] = "tts"
                    it[name] = "legacy public"
                    it[SourceSubscriptions.url] = link
                    it[isPublic] = true
                    it[createdAt] = 0
                }.value
            }
            assertEquals(0, ttsSubscriptions.list(other).size)
            assertFailsWith<ForbiddenException> { ttsSubscriptions.delete(admin, legacyPublic) }

            val sourceId = dbQuery {
                SourceSubscriptions.insertAndGetId {
                    it[ownerId] = owner.id
                    it[kind] = "source"
                    it[name] = "source sub"
                    it[SourceSubscriptions.url] = link
                    it[createdAt] = 0
                }.value
            }
            sourceSubscriptions.delete(admin, sourceId)
            assertTrue(sourceSubscriptions.list(owner).isEmpty())
        } finally {
            server.stop(0)
        }
    }
}
