package io.legado.web

import io.legado.web.auth.UserPrincipal
import io.legado.web.config.AppSettings
import io.legado.web.config.DbSettings
import io.legado.web.db.Books
import io.legado.web.db.DatabaseFactory
import io.legado.web.db.Users
import io.legado.web.db.dbQuery
import io.legado.web.service.ReplaceRuleService
import io.legado.web.service.SettingService
import io.legado.web.service.ShelfService
import io.legado.web.service.SourceService
import io.legado.web.service.UserService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressOfflineSyncTest {
    @Test
    fun offlineProgressNeverOverwritesNewerOnlineProgress() = runBlocking {
        val dir = Files.createTempDirectory("progress-offline-").toFile().apply { deleteOnExit() }
        val db = DbSettings("sqlite", "jdbc:sqlite:${dir.absolutePath}/t.db", "", "", 1)
        DatabaseFactory.init(db)
        val (userId, bookId) = transaction(DatabaseFactory.database) {
            val uid = Users.insertAndGetId {
                it[username] = "reader"
                it[passwordHash] = "unused"
                it[createdAt] = 0
            }.value
            uid to Books.insertAndGetId {
                it[Books.userId] = uid
                it[bookUrl] = "http://book"
                it[origin] = "http://src"
                it[name] = "B"
                it[createdAt] = 0
            }.value
        }
        val settings = SettingService()
        val app = AppSettings(dir, db, "j", "e", "i", 1, emptyList(), true)
        val shelf = ShelfService(SourceService(settings, UserService(settings)), ReplaceRuleService(), app)
        val user = UserPrincipal(userId, "reader", "user")
        suspend fun saved() = dbQuery {
            Books.selectAll().where { Books.id eq bookId }.single().let { it[Books.durChapterIndex] to it[Books.durChapterTime] }
        }

        // 飞机上的设备 30 秒前读到第 8 章，然后另一台在线设备刚刚读到第 20 章
        val offlineAt = System.currentTimeMillis() - 30_000
        assertTrue(shelf.saveProgress(user, bookId, 20, 0, "c20"))
        assertFalse(shelf.saveProgress(user, bookId, 8, 0, "c8", offlineAt), "older offline progress must be dropped even inside the old 5-minute window")
        assertEquals(20, saved().first)

        // 反过来：在线设备停在第 20 章不动，离线设备读到第 30 章后联网补传，应当被接受
        val newerOffline = System.currentTimeMillis()
        assertTrue(shelf.saveProgress(user, bookId, 30, 0, "c30", newerOffline))
        assertEquals(30 to newerOffline, saved())

        // 手机时间超前时按服务器时间封顶，之后的在线进度仍然能写入
        assertTrue(shelf.saveProgress(user, bookId, 31, 0, "c31", System.currentTimeMillis() + 86_400_000))
        assertTrue(saved().second <= System.currentTimeMillis())
        assertTrue(shelf.saveProgress(user, bookId, 32, 0, "c32"))
        assertEquals(32, saved().first)
    }
}
