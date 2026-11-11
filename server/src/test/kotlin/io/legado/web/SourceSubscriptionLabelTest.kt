package io.legado.web

import com.sun.net.httpserver.HttpServer
import io.legado.web.auth.UserPrincipal
import io.legado.web.config.DbSettings
import io.legado.web.db.BookSources
import io.legado.web.db.DatabaseFactory
import io.legado.web.db.Settings
import io.legado.web.db.SourceSubscriptionItems
import io.legado.web.db.Users
import io.legado.web.db.dbQuery
import io.legado.web.service.SettingService
import io.legado.web.service.SourceService
import io.legado.web.service.SubscriptionService
import io.legado.web.service.UserService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceSubscriptionLabelTest {
    @Test
    fun sourceShowsEveryContainingSubscriptionAndFallsBackToOwner() = runBlocking {
        val file = Files.createTempFile("source-subscription-label-", ".db").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${file.absolutePath}", "", "", 1))
        val sourceUrl = "http://127.0.0.1:1/source-a"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/feed") { exchange ->
            val bytes = """[{"bookSourceUrl":"$sourceUrl","bookSourceName":"Source A"}]""".toByteArray()
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
            val ownerId = transaction(DatabaseFactory.database) {
                Users.insertAndGetId {
                    it[username] = "owner"
                    it[passwordHash] = "unused"
                    it[Users.role] = "admin"
                    it[createdAt] = 0
                }.value
            }
            val settings = SettingService()
            val sources = SourceService(settings, UserService(settings))
            val subscriptions = SubscriptionService(sources)
            val owner = UserPrincipal(ownerId, "owner", "admin")
            val feed = "http://127.0.0.1:${server.address.port}/feed"
            val first = subscriptions.create(owner, "alpha", feed, false, true)
            subscriptions.create(owner, "beta", feed, false, true)

            val imported = sources.list(owner).single { it.bookSourceUrl == sourceUrl }
            assertEquals(listOf("alpha", "beta"), imported.subscriptionNames)
            assertEquals("owner", imported.ownerUsername)

            val manualId = dbQuery {
                BookSources.insertAndGetId {
                    it[BookSources.ownerId] = ownerId
                    it[BookSources.url] = "https://manual.example/1"
                    it[BookSources.name] = "manual"
                    it[BookSources.json] = "{}"
                    it[BookSources.createdAt] = 0
                }.value
            }
            assertTrue(sources.list(owner).single { it.id == manualId }.subscriptionNames.isEmpty())

            val request = SourceService.PublicSyncRequest(listOf(imported.id), "incremental", null)
            val preview = sources.syncPublic(owner, request, false)
            sources.syncPublic(owner, request.copy(revision = preview.revision), true)
            val published = sources.list(owner, management = true).single { it.isPublic && it.bookSourceUrl == sourceUrl }
            assertEquals(listOf("alpha", "beta"), published.subscriptionNames)
            subscriptions.refresh(owner, first)
            assertEquals(listOf("alpha", "beta"),
                sources.list(owner, management = true).single { it.isPublic && it.bookSourceUrl == sourceUrl }.subscriptionNames,
                "refreshing a subscription must keep links on published copies")

            subscriptions.delete(owner, first)
            assertEquals(listOf("beta"), sources.list(owner).single { it.bookSourceUrl == sourceUrl }.subscriptionNames)
            assertEquals(listOf("beta"), sources.list(owner, management = true).single { it.isPublic && it.bookSourceUrl == sourceUrl }.subscriptionNames)

            subscriptions.create(owner, "gamma", feed, false, true)
            val linkOnly = sources.syncPublic(owner, request, false)
            assertEquals(1, linkOnly.updated, "a subscription-only change must count as an update")
            sources.syncPublic(owner, request.copy(revision = linkOnly.revision), true)
            assertEquals(listOf("beta", "gamma"),
                sources.list(owner, management = true).single { it.isPublic && it.bookSourceUrl == sourceUrl }.subscriptionNames)

            sources.delete(owner, listOf(imported.id))
            val remaining = dbQuery {
                SourceSubscriptionItems.selectAll().where { SourceSubscriptionItems.sourceId eq imported.id }.count()
            }
            assertEquals(0, remaining)
        } finally {
            server.stop(0)
        }
    }
}
