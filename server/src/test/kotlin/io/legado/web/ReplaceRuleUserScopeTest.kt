package io.legado.web

import io.legado.app.data.entities.Book
import io.legado.app.help.engine.Engine
import io.legado.app.help.engine.EngineContext
import io.legado.app.help.http.CookieStore
import io.legado.web.auth.UserPrincipal
import io.legado.web.config.DbSettings
import io.legado.web.db.DatabaseFactory
import io.legado.web.db.DbEngineStore
import io.legado.web.service.ReplaceRuleDto
import io.legado.web.service.ReplaceRuleService
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplaceRuleUserScopeTest {
    @Test
    fun jsReplacementSeesOnlyTheReadersOwnEngineData() = runBlocking {
        val file = Files.createTempFile("replace-scope-", ".db").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${file.absolutePath}", "", "", 1))
        val oldStore = Engine.store
        Engine.store = DbEngineStore()
        try {
            val service = ReplaceRuleService()
            for (uid in listOf(1L, 2L)) {
                EngineContext.with(EngineContext(uid)) { CookieStore.setCookie("https://example.com/", "sid=user$uid") }
                service.save(uid, ReplaceRuleDto(name = "cookie", pattern = "MARK", replacement = "@js:java.getCookie('https://example.com/')"))
            }
            val book = Book(bookUrl = "https://example.com/book", name = "book")
            assertEquals("sid=user1", service.apply(UserPrincipal(1, "a", "admin"), book, "t", "MARK"))
            assertEquals("sid=user2", service.apply(UserPrincipal(2, "b", "admin"), book, "t", "MARK"))
        } finally {
            Engine.store = oldStore
        }
    }
}
