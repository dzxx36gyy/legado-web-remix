package io.legado.web

import io.legado.app.help.CacheManager
import io.legado.app.help.engine.Engine
import io.legado.app.help.engine.EngineContext
import io.legado.app.help.http.CookieStore
import io.legado.web.config.DbSettings
import io.legado.web.db.DatabaseFactory
import io.legado.web.db.DbEngineStore
import io.legado.web.service.BrowserBridge
import io.legado.web.service.SiteAccountService
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SiteAccountServiceTest {
    @Test
    fun clearAllRemovesOnlyCallersPersistentAndSessionCookies() = runBlocking {
        val file = Files.createTempFile("site-account-", ".db").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${file.absolutePath}", "", "", 1))
        val oldStore = Engine.store
        Engine.store = DbEngineStore()
        try {
            for (uid in listOf(1L, 2L)) {
                EngineContext.with(EngineContext(uid)) {
                    CookieStore.setCookie("https://example.com/", "sid=user$uid")
                    CacheManager.putMemory("example.com_session_cookie", "session=user$uid")
                }
                Engine.store.putCookie(uid, "webview|example.com", "browser=user$uid")
                Engine.store.putCookie(uid, "webview|other.org", "browser=other$uid")
            }
            val service = SiteAccountService(BrowserBridge()) { _, _, _ -> }
            service.clearAll(1)
            assertEquals(emptyList(), service.list(1))
            assertEquals(listOf("example.com", "other.org"), service.list(2).map { it.domain })
            EngineContext.with(EngineContext(1)) {
                assertEquals("", CookieStore.getCookie("https://example.com/"))
                assertNull(CacheManager.getFromMemory("example.com_session_cookie"))
            }
            EngineContext.with(EngineContext(2)) {
                assertEquals("sid=user2", CacheManager.getFromMemory("example.com_cookie"))
                assertEquals("session=user2", CacheManager.getFromMemory("example.com_session_cookie"))
            }
            assertEquals("browser=user2", Engine.store.getCookie(2, "webview|example.com"))
        } finally { Engine.store = oldStore }
    }
}
