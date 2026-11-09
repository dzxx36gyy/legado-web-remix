package io.legado.web

import com.sun.net.httpserver.HttpServer
import io.legado.web.config.DbSettings
import io.legado.web.db.*
import io.legado.web.service.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class SubscriptionScheduleTest {
    @Test
    fun scheduledRefreshFetchesOnlyDueEnabledSubscriptionsAndSkipsDisabledOwners() = runBlocking {
        val file = Files.createTempFile("subscription-schedule-", ".db").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${file.absolutePath}", "", "", 1))
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rules") { exchange ->
            hits.incrementAndGet()
            val bytes = "[]".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            transaction(DatabaseFactory.database) {
                val active = Users.insertAndGetId { it[username] = "active"; it[passwordHash] = "unused"; it[role] = "admin"; it[createdAt] = 0 }.value
                val disabled = Users.insertAndGetId { it[username] = "disabled"; it[passwordHash] = "unused"; it[role] = "admin"; it[enabled] = false; it[createdAt] = 0 }.value
                listOf(Triple(active, true, 0L), Triple(active, true, System.currentTimeMillis()), Triple(active, false, 0L), Triple(disabled, true, 0L)).forEach { (owner, auto, last) ->
                    SourceSubscriptions.insertAndGetId {
                        it[ownerId] = owner; it[kind] = "replace"; it[name] = "Scheduled"
                        it[url] = "http://127.0.0.1:${server.address.port}/rules"
                        it[autoUpdate] = auto; it[lastUpdateAt] = last; it[createdAt] = 0
                    }
                }
            }
            val settings = SettingService()
            val source = SourceService(settings, UserService(settings))
            val subscriptions = SubscriptionService(source, ruleKind = "replace", importRules = { _, _ -> 0 })
            subscriptions.refreshAll()
            subscriptions.refreshAll()
            assertEquals(1, hits.get())
        } finally { server.stop(0) }
    }
}
