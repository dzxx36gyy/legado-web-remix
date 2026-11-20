package io.legado.web.webview

import io.legado.web.config.DbSettings
import io.legado.web.db.DatabaseFactory
import io.legado.web.db.UserPrefs
import io.legado.web.db.Users
import io.legado.web.db.dbQuery
import io.legado.web.service.SettingService
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import io.legado.web.auth.BadRequestException
import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

class BrowserRoutingTest {
    @Test
    fun globalRoutingTracksEnabledAdminsLiveWithoutReplacingManualRules() = runBlocking {
        val dir = Files.createTempDirectory("browser-site-sharing-").toFile()
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:$dir/test.db", "", "", 1))
        val settings = SettingService()
        settings.update(mapOf(SettingService.CLOAK_BROWSER_SITES to "manual.example\ncommon.example"))
        val (first, second, reader) = dbQuery {
            listOf("admin", "admin", "user").mapIndexed { index, role ->
                Users.insertAndGetId {
                    it[username] = "account$index"
                    it[passwordHash] = "test"
                    it[Users.role] = role
                    it[createdAt] = 0
                }.value
            }
        }
        BrowserRouter(settings, BrowserContexts(dir.resolve("contexts"))).use { router ->
            fun usesCloak(host: String, userId: Long = reader) = router.destination("https://$host", userId).cloak
            suspend fun share(account: Long, enabled: Boolean) = dbQuery {
                UserPrefs.upsert {
                    it[userId] = account
                    it[key] = "_cloakBrowserSync"
                    it[value] = enabled.toString()
                }
            }
            router.remember("https://private.example", first, false)
            router.remember("https://common.example", first, false)
            router.remember("https://common.example", second, false)
            router.remember("https://shared.example", first, false)
            router.remember("https://shared.example", second, false)
            router.remember("https://reader.example", reader, false)
            assertEquals(true, usesCloak("private.example", first))
            assertEquals(false, usesCloak("private.example"))
            share(first, true)
            share(second, true)
            assertEquals(true, usesCloak("private.example"))
            assertEquals(true, usesCloak("shared.example"))
            router.remember("https://new.example", first, false)
            assertEquals(true, usesCloak("new.example"))
            dbQuery {
                UserPrefs.update({ (UserPrefs.userId eq first) and (UserPrefs.key eq SettingService.CLOAK_BROWSER_SITES) }) {
                    it[value] = "shared.example\nnew.example"
                }
            }
            assertEquals(false, usesCloak("private.example"))
            share(first, false)
            assertEquals(true, usesCloak("new.example", first))
            assertEquals(false, usesCloak("new.example"))
            assertEquals(true, usesCloak("shared.example"))
            dbQuery { Users.update({ Users.id eq second }) { it[enabled] = false } }
            assertEquals(false, usesCloak("shared.example"))
            assertEquals(true, usesCloak("common.example"))
            assertEquals(true, usesCloak("manual.example"))
            share(reader, true)
            assertEquals(false, usesCloak("reader.example", first))
        }
    }

    @Test
    fun exactAndWildcardDomainsChooseCloakWithoutMatchingUnrelatedHosts() = runBlocking {
        val dir = Files.createTempDirectory("browser-routing-").toFile()
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:$dir/test.db", "", "", 1))
        val settings = SettingService()
        settings.update(mapOf(SettingService.BROWSER_CDP to "http://browser:9222", SettingService.CLOAK_BROWSER_CDP to "http://cloak:9222",
            SettingService.CLOAK_BROWSER_SITES to "Example.com, *.books.example.org"))
        BrowserRouter(settings, BrowserContexts(dir.resolve("contexts"))).use { router ->
            router.remember("https://personal.example", 10, false)
            assertEquals(BrowserRouter.Destination("http://cloak:9222", true), router.destination("https://personal.example", 10))
            assertEquals(BrowserRouter.Destination("http://browser:9222", false), router.destination("https://personal.example", 11))
            router.remember("https://shared.example", 1, true)
            assertEquals(BrowserRouter.Destination("http://cloak:9222", true), router.destination("https://shared.example", 11))
            listOf("https://example.com/a", "https://a.books.example.org/a").forEach {
                assertEquals(BrowserRouter.Destination("http://cloak:9222", true), router.destination(it))
            }
            listOf("https://example.com.evil.test", "https://sub.example.com", "https://books.example.org", "https://other.test").forEach {
                assertEquals(BrowserRouter.Destination("http://browser:9222", false), router.destination(it))
            }
            settings.update(mapOf(SettingService.CLOAK_BROWSER_CDP to ""))
            assertFailsWith<NoStackTraceException> { router.destination("https://example.com") }
            assertFailsWith<BadRequestException> { settings.update(mapOf(SettingService.CLOAK_BROWSER_SITES to "https://example.com/path")) }
        }
    }
}
