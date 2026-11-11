package io.legado.web.service

import io.legado.web.auth.BadRequestException
import io.legado.web.auth.NotFoundException
import io.legado.web.db.Books
import io.legado.web.db.ReadingStats
import io.legado.web.db.dbQuery
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** 阅读页定时上报的阅读时长、字数（增量） */
data class ReadingReport(
    /** 阅读者本地日期 yyyy-MM-dd（按用户所在时区统计“今天”） */
    val day: String = "",
    val bookId: Long = 0,
    val seconds: Int = 0,
    val chars: Int = 0,
)

data class DayStat(val day: String, val seconds: Long, val chars: Long)
data class BookStat(val bookId: Long, val name: String, val author: String, val seconds: Long, val chars: Long)

data class HiddenBook(val bookId: Long, val name: String, val author: String)

data class ReadingSummary(
    val totalSeconds: Long,
    val totalChars: Long,
    /** 有阅读记录的天数 */
    val totalDays: Int,
    /** from 起每一天的统计（没有阅读的日子不返回） */
    val days: List<DayStat>,
    /** 阅读时间最长的书架上的书 */
    val books: List<BookStat>,
    /** 不在书架上（发现页浏览过、已删除）的书合计 */
    val otherSeconds: Long = 0,
    val otherChars: Long = 0,
    /** 用户隐藏的书，不计入以上任何数据 */
    val hidden: List<HiddenBook> = emptyList(),
)

/**
 * 阅读统计：按用户、日期、书籍累计阅读时长和字数
 */
class ReadingStatsService {

    suspend fun report(userId: Long, r: ReadingReport) {
        if (!DAY.matches(r.day)) throw BadRequestException("日期格式错误")
        if (r.bookId <= 0) throw BadRequestException("书籍无效")
        // 每次上报的是一小段时间内的增量，限制范围防止异常数据
        val seconds = r.seconds.coerceIn(0, 900)
        val chars = r.chars.coerceIn(0, 500_000)
        if (seconds == 0 && chars == 0) return
        dbQuery {
            val where = (ReadingStats.userId eq userId) and (ReadingStats.day eq r.day) and (ReadingStats.bookId eq r.bookId)
            val row = ReadingStats.selectAll().where { where }.firstOrNull()
            if (row == null) {
                ReadingStats.insert {
                    it[ReadingStats.userId] = userId
                    it[day] = r.day
                    it[bookId] = r.bookId
                    it[ReadingStats.seconds] = seconds.toLong()
                    it[ReadingStats.chars] = chars.toLong()
                }
            } else {
                ReadingStats.update({ where }) {
                    it[ReadingStats.seconds] = ReadingStats.seconds + seconds.toLong()
                    it[ReadingStats.chars] = ReadingStats.chars + chars.toLong()
                }
            }
        }
    }

    suspend fun summary(userId: Long, from: String): ReadingSummary {
        if (!DAY.matches(from)) throw BadRequestException("日期格式错误")
        return dbQuery {
            val all = ReadingStats.selectAll().where { ReadingStats.userId eq userId }.toList()
            val bookIds = all.map { it[ReadingStats.bookId] }.distinct()
            val info = bookIds.chunked(500).flatMap { ids ->
                Books.select(Books.id, Books.name, Books.author, Books.inShelf, Books.statsHidden)
                    .where { (Books.userId eq userId) and (Books.id inList ids) }
                    .toList()
            }.associateBy { it[Books.id].value }
            val hiddenIds = info.values.filter { it[Books.statsHidden] }.map { it[Books.id].value }.toSet()
            val rows = all.filter { it[ReadingStats.bookId] !in hiddenIds }
            val days = rows.filter { it[ReadingStats.day] >= from }
                .groupBy { it[ReadingStats.day] }
                .map { (d, rs) -> DayStat(d, rs.sumOf { it[ReadingStats.seconds] }, rs.sumOf { it[ReadingStats.chars] }) }
                .sortedBy { it.day }
            val (shelfRows, otherRows) = rows.partition { info[it[ReadingStats.bookId]]?.get(Books.inShelf) == true }
            val top = shelfRows.groupBy { it[ReadingStats.bookId] }
                .map { (id, rs) ->
                    val book = info.getValue(id)
                    BookStat(id, book[Books.name], book[Books.author],
                        rs.sumOf { it[ReadingStats.seconds] }, rs.sumOf { it[ReadingStats.chars] })
                }
                .sortedByDescending { it.seconds }
                .take(10)
            ReadingSummary(
                totalSeconds = rows.sumOf { it[ReadingStats.seconds] },
                totalChars = rows.sumOf { it[ReadingStats.chars] },
                totalDays = rows.map { it[ReadingStats.day] }.distinct().size,
                days = days,
                books = top,
                otherSeconds = otherRows.sumOf { it[ReadingStats.seconds] },
                otherChars = otherRows.sumOf { it[ReadingStats.chars] },
                hidden = hiddenIds.map { id -> info.getValue(id).let { HiddenBook(id, it[Books.name], it[Books.author]) } }
                    .sortedBy { it.name },
            )
        }
    }

    suspend fun setHidden(userId: Long, bookId: Long, hidden: Boolean) {
        val updated = dbQuery {
            Books.update({ (Books.id eq bookId) and (Books.userId eq userId) }) { it[statsHidden] = hidden }
        }
        if (updated == 0) throw NotFoundException("书籍不存在")
    }

    companion object {
        private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
