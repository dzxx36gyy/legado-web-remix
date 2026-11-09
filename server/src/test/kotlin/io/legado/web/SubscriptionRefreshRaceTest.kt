package io.legado.web

import com.sun.net.httpserver.HttpServer
import io.legado.web.auth.UserPrincipal
import io.legado.web.config.DbSettings
import io.legado.web.db.DatabaseFactory
import io.legado.web.db.Settings
import io.legado.web.db.SourceSubscriptionItems
import io.legado.web.db.SourceSubscriptions
import io.legado.web.db.Users
import io.legado.web.db.dbQuery
import io.legado.web.service.SettingService
import io.legado.web.service.SourceService
import io.legado.web.service.SubscriptionService
import io.legado.web.service.UserService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubscriptionRefreshRaceTest {
    @Test
    fun deletingSubscriptionDuringRefreshLeavesNoLinks() = runBlocking {
        val file = Files.createTempFile("subscription-race-", ".db").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${file.absolutePath}", "", "", 1))
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/feed") { exchange ->
            arrived.countDown()
            release.await(20, TimeUnit.SECONDS)
            val bytes = """[{"bookSourceUrl":"http://127.0.0.1:1/src","bookSourceName":"S"}]""".toByteArray()
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
                    it[createdAt] = 0
                }.value
            }
            val settings = SettingService()
            val sources = SourceService(settings, UserService(settings))
            val subscriptions = SubscriptionService(sources)
            val owner = UserPrincipal(ownerId, "owner", "user")
            val id = dbQuery {
                SourceSubscriptions.insertAndGetId {
                    it[SourceSubscriptions.ownerId] = ownerId
                    it[kind] = "source"
                    it[name] = "slow"
                    it[SourceSubscriptions.url] = "http://127.0.0.1:${server.address.port}/feed"
                    it[createdAt] = 0
                }.value
            }
            val refresh = async(Dispatchers.IO) { runCatching { subscriptions.refresh(owner, id) } }
            assertTrue(arrived.await(20, TimeUnit.SECONDS), "refresh never reached the feed")
            subscriptions.delete(owner, id)
            release.countDown()
            refresh.await()
            val links = dbQuery {
                SourceSubscriptionItems.selectAll().where { SourceSubscriptionItems.subscriptionId eq id }.count()
            }
            assertEquals(0, links, "links left for a deleted subscription")
        } finally {
            release.countDown()
            server.stop(0)
        }
    }
}
