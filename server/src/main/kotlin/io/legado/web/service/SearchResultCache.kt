package io.legado.web.service

import io.legado.web.security.ScriptUrls
import java.time.Clock
import java.time.Duration

/** 仅保存成功的单书源搜索；与 ScriptUrls 一样随进程退出清空。 */
class SearchResultCache(
    private val ttl: Duration = Duration.ofHours(1),
    private val clock: Clock = Clock.systemUTC(),
    private val totalCap: Int = MAX_ENTRIES,
    private val userCap: Int = MAX_PER_USER,
) {
    data class Key(val userId: Long, val sourceId: Long, val query: String, val page: Int)
    data class Lookup(val generation: Long, val books: List<SearchResultItem>?)
    private data class Entry(val books: List<SearchResultItem>, val expiresAt: java.time.Instant)

    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private var generation = 0L

    @Synchronized
    fun lookup(key: Key, refresh: Boolean = false): Lookup {
        if (ttl.isZero || refresh) return Lookup(generation, null)
        val entry = entries[key] ?: return Lookup(generation, null)
        if (!clock.instant().isBefore(entry.expiresAt)) {
            entries.remove(key)
            return Lookup(generation, null)
        }
        entry.books.forEach { ScriptUrls.remember(key.userId, it.bookUrl, it.tocUrl, it.coverUrl) }
        return Lookup(generation, entry.books)
    }

    /** 只有正常返回才写入，异常、超时和取消均不缓存。 */
    suspend fun load(key: Key, lookup: Lookup, fetch: suspend () -> List<SearchResultItem>): List<SearchResultItem> {
        lookup.books?.let { return it }
        val books = fetch()
        store(key, lookup.generation, books)
        return books
    }

    @Synchronized
    private fun store(key: Key, startedGeneration: Long, books: List<SearchResultItem>) {
        // 失效前启动的请求不能把旧结果重新放回缓存。
        if (ttl.isZero || startedGeneration != generation) return
        entries.entries.removeIf { !clock.instant().isBefore(it.value.expiresAt) }
        entries[key] = Entry(books.toList(), clock.instant().plus(ttl))
        while (entries.keys.count { it.userId == key.userId } > userCap) {
            entries.remove(entries.keys.first { it.userId == key.userId })
        }
        while (entries.size > totalCap) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun invalidateSource(sourceId: Long) {
        generation++
        entries.keys.removeIf { it.sourceId == sourceId }
    }

    companion object {
        const val MAX_ENTRIES = 5000
        const val MAX_PER_USER = 500
    }
}
