package io.legado.web.webview

import io.legado.web.config.DbSettings
import io.legado.web.db.DatabaseFactory
import io.legado.web.service.SettingService
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import io.legado.web.auth.BadRequestException
import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.runBlocking

class BrowserRoutingTest {
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
