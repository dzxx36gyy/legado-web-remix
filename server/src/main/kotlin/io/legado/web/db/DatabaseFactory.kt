package io.legado.web.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.legado.web.config.DbSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.Connection

object DatabaseFactory {

    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    lateinit var database: Database
        private set

    var isSqlite = true
        private set

    fun init(settings: DbSettings) {
        isSqlite = settings.type == "sqlite"
        val config = HikariConfig().apply {
            jdbcUrl = settings.url
            if (isSqlite) {
                driverClassName = "org.sqlite.JDBC"
                // SQLite 只允许单写，使用单连接避免 "database is locked"
                maximumPoolSize = 1
                addDataSourceProperty("journal_mode", "WAL")
                addDataSourceProperty("busy_timeout", "10000")
                addDataSourceProperty("synchronous", "NORMAL")
            } else {
                driverClassName = "org.postgresql.Driver"
                username = settings.user
                password = settings.password
                maximumPoolSize = settings.maxPoolSize
            }
            isAutoCommit = false
            transactionIsolation = if (isSqlite) "TRANSACTION_SERIALIZABLE" else "TRANSACTION_READ_COMMITTED"
            validate()
        }
        val dataSource = HikariDataSource(config)
        database = Database.connect(dataSource)
        if (isSqlite) {
            org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.manager.defaultIsolationLevel =
                Connection.TRANSACTION_SERIALIZABLE
        }
        transaction(database) {
            SchemaUtils.create(*allTables)
            // 补齐新增的列（升级兼容）
            val stmts = SchemaUtils.addMissingColumnsStatements(*allTables)
            stmts.forEach { exec(it) }
        }
        ensureUniqueBooks()
        repairLegacySourceOrder()
        migratePublicSources()
        cleanOrphanSubscriptionLinks()
        logger.info("数据库初始化完成: {} ({})", settings.type, settings.url.substringBefore('?'))
    }
}

/** 清掉旧版本或中断的刷新留下的悬空关联：订阅或书源已不存在时该行不再有意义 */
private fun cleanOrphanSubscriptionLinks() = transaction(DatabaseFactory.database) {
    exec(
        "DELETE FROM source_subscription_items WHERE subscription_id NOT IN (SELECT id FROM source_subscriptions) " +
            "OR source_id NOT IN (SELECT id FROM book_sources)"
    )
}

/** 保留公共记录和 ID，并一次性创建私有编辑副本；标记避免重启后重新发布或复制。 */
private fun migratePublicSources() = transaction(DatabaseFactory.database) {
    exec("CREATE UNIQUE INDEX IF NOT EXISTS ux_source_owner_url_scope ON book_sources (owner_id,url,is_public)")
    exec("DROP INDEX IF EXISTS ux_source_owner_url")
    val marker = "public_source_snapshots_v1"
    val done = Settings.selectAll().where { Settings.key eq marker }.any()
    if (!done) {
        val columns = "owner_id,url,name,group_name,type,enabled,enabled_explore,has_explore,has_login,custom_order,weight,respond_time,comment,json,last_update_time,created_at"
        exec("INSERT INTO book_sources ($columns,is_public) SELECT $columns,FALSE FROM book_sources p WHERE p.is_public=TRUE AND NOT EXISTS (SELECT 1 FROM book_sources s WHERE s.owner_id=p.owner_id AND s.url=p.url AND s.is_public=FALSE)")
        Settings.insert { it[key] = marker; it[value] = "true" }
    }
}

/**
 * 同一用户同一 bookUrl 只保留一条书籍记录。旧版本没有这个约束，并发打开或导入可能产生重复，
 * 建索引失败时先合并重复（保留在书架上、最近阅读的那条），再建索引
 */
private fun ensureUniqueBooks() {
    val sql = "CREATE UNIQUE INDEX IF NOT EXISTS ux_books_user_url ON books (user_id, book_url)"
    val ok = runCatching { transaction(DatabaseFactory.database) { exec(sql) } }.isSuccess
    if (ok) return
    transaction(DatabaseFactory.database) {
        // 被合并的记录 id -> 保留的记录 id
        val keepOf = HashMap<Long, Long>()
        Books.select(Books.id, Books.userId, Books.bookUrl, Books.inShelf, Books.durChapterTime).toList()
            .groupBy { it[Books.userId] to it[Books.bookUrl] }.values
            .filter { it.size > 1 }
            .forEach { group ->
                val keep = group.maxWith(compareBy({ it[Books.inShelf] }, { it[Books.durChapterTime] }))
                group.filter { it !== keep }.forEach { keepOf[it[Books.id].value] = keep[Books.id].value }
            }
        // 书签、阅读统计转到保留的记录上，不随重复记录一起删掉
        keepOf.entries.groupBy({ it.value }, { it.key }).forEach { (keepId, ids) ->
            Bookmarks.update({ Bookmarks.bookId inList ids }) { it[bookId] = keepId }
            ReadingStats.selectAll().where { ReadingStats.bookId inList ids }.toList().forEach { r ->
                val user = r[ReadingStats.userId]
                val day = r[ReadingStats.day]
                val target = (ReadingStats.userId eq user) and (ReadingStats.day eq day) and (ReadingStats.bookId eq keepId)
                val updated = ReadingStats.update({ target }) {
                    it[seconds] = seconds + r[ReadingStats.seconds]
                    it[chars] = chars + r[ReadingStats.chars]
                }
                if (updated == 0) ReadingStats.insert {
                    it[userId] = user
                    it[ReadingStats.day] = day
                    it[bookId] = keepId
                    it[seconds] = r[ReadingStats.seconds]
                    it[chars] = r[ReadingStats.chars]
                }
            }
        }
        keepOf.keys.toList().chunked(500).forEach { ids ->
            Books.deleteWhere { Books.id inList ids }
            Chapters.deleteWhere { Chapters.bookId inList ids }
            ChapterContents.deleteWhere { ChapterContents.bookId inList ids }
            ReadingStats.deleteWhere { ReadingStats.bookId inList ids }
        }
        exec(sql)
    }
}

/** 在 IO 线程执行数据库事务 */
suspend fun <T> dbQuery(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) {
    transaction(DatabaseFactory.database) { block() }
}

/** 同步事务（用于引擎回调等非挂起场景） */
fun <T> dbBlocking(block: JdbcTransaction.() -> T): T = transaction(DatabaseFactory.database) { block() }
