package io.legado.web

import io.legado.web.auth.UserPrincipal
import io.legado.web.config.DbSettings
import io.legado.web.db.BookSources
import io.legado.web.db.DatabaseFactory
import io.legado.web.db.Users
import io.legado.web.service.SettingService
import io.legado.web.service.SourceService
import io.legado.web.service.UserService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.sql.Connection
import java.sql.PreparedStatement
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceListOwnerQueryTest {
    @Test
    fun cachedChapterCountsUseConstantStatementsAndMatchCurrentChapterIdentity() = runBlocking {
        val dir = Files.createTempDirectory("cached-counts-").toFile().apply { deleteOnExit() }
        val db = DbSettings("sqlite", "jdbc:sqlite:${dir.absolutePath}/counts.db", "", "", 1)
        DatabaseFactory.init(db)
        val ids = transaction(DatabaseFactory.database) {
            (1..21).map { n ->
                val id = io.legado.web.db.Books.insertAndGetId {
                    it[userId] = if (n == 21) 2 else 1
                    it[bookUrl] = "https://book/$n"; it[origin] = "https://source"
                    it[name] = "book$n"; it[createdAt] = 0
                }.value
                if (n != 20) repeat(3) { index ->
                    io.legado.web.db.Chapters.insert {
                        it[bookId] = id; it[io.legado.web.db.Chapters.index] = index
                        it[url] = "https://chapter/$n/$index"; it[title] = "chapter"
                    }
                    io.legado.web.db.ChapterContents.insert {
                        it[bookId] = id; it[io.legado.web.db.ChapterContents.index] = index
                        it[chapterUrl] = if (index == 2) "https://stale" else "https://chapter/$n/$index"
                        it[content] = "text"; it[updatedAt] = 0
                    }
                }
                io.legado.web.db.ChapterContents.insert {
                    it[bookId] = id; it[index] = 99; it[chapterUrl] = "https://orphan"
                    it[content] = "orphan"; it[updatedAt] = 0
                }
                id
            }
        }
        val counting = CountingDataSource(org.sqlite.SQLiteDataSource().apply { url = db.url })
        val field = DatabaseFactory::class.java.getDeclaredField("database").apply { isAccessible = true }
        val original = DatabaseFactory.database
        field.set(DatabaseFactory, Database.connect(counting))
        try {
            val settings = SettingService()
            val app = io.legado.web.config.AppSettings(dir, db, "j", "e", "i", 1, emptyList(), true)
            val shelf = io.legado.web.service.ShelfService(SourceService(settings, UserService(settings)), io.legado.web.service.ReplaceRuleService(), app)
            val user = UserPrincipal(1, "reader", "user")
            counting.executed.clear()
            assertEquals(mapOf(ids.first() to 2), shelf.cachedChapterCounts(user, ids.take(1)))
            val single = counting.executed.size
            counting.executed.clear()
            assertEquals(ids.take(20).associateWith { if (it == ids[19]) 0 else 2 }, shelf.cachedChapterCounts(user, ids + ids.first()))
            assertEquals(single, counting.executed.size, "statement count must not grow with book count")
            assertEquals(1, single)
        } finally {
            field.set(DatabaseFactory, original)
            dir.deleteRecursively()
        }
    }

    @Test
    fun listLoadsOwnerNamesOnceRegardlessOfSourceCount() = runBlocking {
        val file = Files.createTempFile("source-list-owner-", ".db").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${file.absolutePath}", "", "", 1))
        val owners = transaction(DatabaseFactory.database) {
            (1..3).map { index ->
                Users.insertAndGetId {
                    it[username] = "user$index"
                    it[passwordHash] = "unused"
                    it[role] = "user"
                    it[createdAt] = 0
                }.value
            }
        }
        transaction(DatabaseFactory.database) {
            owners.forEach { owner ->
                repeat(12) { index ->
                    BookSources.insert {
                        it[ownerId] = owner
                        it[url] = "https://owner$owner.example/$index"
                        it[name] = "private $owner-$index"
                        it[json] = "{}"
                        it[createdAt] = 0
                    }
                }
            }
            BookSources.insert {
                it[ownerId] = owners.first()
                it[url] = "https://public.example/1"
                it[name] = "public"
                it[json] = "{}"
                it[isPublic] = true
                it[createdAt] = 0
            }
            BookSources.insert {
                it[ownerId] = owners.first()
                it[url] = "https://owner${owners[1]}.example/0"
                it[name] = "public shadowed by private"
                it[json] = "{}"
                it[isPublic] = true
                it[createdAt] = 0
            }
        }
        val counting = CountingDataSource(org.sqlite.SQLiteDataSource().apply { url = "jdbc:sqlite:${file.absolutePath}" })
        val field = DatabaseFactory::class.java.getDeclaredField("database").apply { isAccessible = true }
        val original = DatabaseFactory.database
        field.set(DatabaseFactory, Database.connect(counting))
        try {
            val settings = SettingService()
            val service = SourceService(settings, UserService(settings))
            val viewer = UserPrincipal(owners[1], "user2", "user")

            counting.executed.clear()
            val visible = service.list(viewer)
            val visibleQueries = counting.executed.size
            assertEquals(13, visible.size)
            assertEquals(1, counting.executed.count { it.contains("from users", ignoreCase = true) })
            assertEquals(4, visibleQueries, "list must stay at 4 statements regardless of row count")
            visible.forEach { assertEquals("user${it.ownerId}", it.ownerUsername) }
            visible.forEach { assertTrue(it.subscriptionNames.isEmpty()) }

            counting.executed.clear()
            val management = service.list(viewer, management = true)
            val managementQueries = counting.executed.size
            assertEquals(14, management.size)
            assertEquals(1, counting.executed.count { it.contains("from users", ignoreCase = true) })
            assertEquals(visibleQueries, managementQueries, "query count must not grow with rows")
            management.forEach { assertEquals("user${it.ownerId}", it.ownerUsername) }
        } finally {
            field.set(DatabaseFactory, original)
        }
    }
}

private class CountingDataSource(private val delegate: DataSource) : DataSource by delegate {
    val executed = java.util.concurrent.CopyOnWriteArrayList<String>()

    override fun getConnection(): Connection = wrap(delegate.connection)

    override fun getConnection(username: String?, password: String?): Connection =
        wrap(delegate.getConnection(username, password))

    private fun wrap(connection: Connection): Connection = Proxy.newProxyInstance(
        CountingDataSource::class.java.classLoader, arrayOf(Connection::class.java),
    ) { _, method, args ->
        val result = invoke(connection, method, args)
        if (method.name.startsWith("prepare") && result is PreparedStatement) {
            wrapStatement(result, args?.firstOrNull() as? String ?: "")
        } else result
    } as Connection

    private fun wrapStatement(statement: PreparedStatement, sql: String): Any = Proxy.newProxyInstance(
        CountingDataSource::class.java.classLoader, arrayOf(PreparedStatement::class.java),
    ) { _, method, args ->
        if (method.name.startsWith("execute")) executed.add(sql)
        invoke(statement, method, args)
    }

    private fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<Any?>?): Any? = try {
        if (args == null) method.invoke(target) else method.invoke(target, *args)
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }
}
