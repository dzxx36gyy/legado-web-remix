package io.legado.web

import io.legado.web.auth.NotFoundException
import io.legado.web.config.DbSettings
import io.legado.web.db.Books
import io.legado.web.db.DatabaseFactory
import io.legado.web.db.Users
import io.legado.web.service.ReadingReport
import io.legado.web.service.ReadingStatsService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReadingStatsVisibilityTest {
    @Test
    fun hiddenBooksVanishAndOffShelfBooksFoldIntoOther() = runBlocking {
        val dir = Files.createTempDirectory("reading-stats-").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${dir.absolutePath}/t.db", "", "", 1))
        fun user(name: String) = transaction(DatabaseFactory.database) {
            Users.insertAndGetId {
                it[username] = name
                it[passwordHash] = "unused"
                it[createdAt] = 0
            }.value
        }
        val me = user("reader")
        val other = user("someone")
        fun book(owner: Long, name: String, inShelf: Boolean) = transaction(DatabaseFactory.database) {
            Books.insertAndGetId {
                it[userId] = owner
                it[bookUrl] = "http://book/$name"
                it[origin] = "http://src"
                it[Books.name] = name
                it[Books.inShelf] = inShelf
                it[createdAt] = 0
            }.value
        }
        val shelf = book(me, "shelf", true)
        val secret = book(me, "secret", true)
        val browsed = book(me, "browsed", false)
        val deletedId = 99_999L
        val othersBook = book(other, "theirs", true)

        val stats = ReadingStatsService()
        val day = "2026-11-12"
        stats.report(me, ReadingReport(day, shelf, 100, 1000))
        stats.report(me, ReadingReport(day, secret, 200, 2000))
        stats.report(me, ReadingReport(day, browsed, 30, 300))
        stats.report(me, ReadingReport(day, deletedId, 7, 70))

        val before = stats.summary(me, day)
        assertEquals(listOf("secret", "shelf"), before.books.map { it.name })
        assertEquals(37L, before.otherSeconds)
        assertEquals(337L, before.totalSeconds)

        stats.setHidden(me, secret, true)
        val after = stats.summary(me, day)
        assertEquals(listOf("shelf"), after.books.map { it.name })
        assertEquals(listOf(secret), after.hidden.map { it.bookId })
        assertEquals(137L, after.totalSeconds)
        assertEquals(1370L, after.totalChars)
        assertEquals(137L, after.days.single().seconds)
        assertEquals(37L, after.otherSeconds)

        assertFailsWith<NotFoundException> { stats.setHidden(me, othersBook, true) }

        stats.setHidden(me, secret, false)
        assertEquals(337L, stats.summary(me, day).totalSeconds)
    }
}
