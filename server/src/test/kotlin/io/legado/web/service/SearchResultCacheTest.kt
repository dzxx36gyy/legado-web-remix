package io.legado.web.service

import io.legado.web.security.ScriptUrls
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchResultCacheTest {
    private class MutableClock : Clock() {
        var now: Instant = Instant.parse("2026-01-01T00:00:00Z")
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private val key = SearchResultCache.Key(1, 10, "book", 1)
    private val books = listOf(SearchResultItem(
        "book", "author", null, null, null, null, null,
        "https://site/book", "https://site", "site", 10, 0, "https://site/toc", null, 12,
    ))

    @Test
    fun hitsWithinTtlAndExpiresAtDeadline() = runBlocking {
        val clock = MutableClock()
        val cache = SearchResultCache(Duration.ofMinutes(60), clock)
        cache.load(key, cache.lookup(key)) { books }
        clock.now = clock.now.plusSeconds(3599)
        assertEquals(books, cache.load(key, cache.lookup(key)) { error("cache missed") })
        clock.now = clock.now.plusSeconds(1)
        assertNull(cache.lookup(key).books)
    }

    @Test
    fun exceptionsAndCancellationAreNotCached() = runBlocking {
        val cache = SearchResultCache()
        assertFailsWith<IllegalStateException> {
            cache.load(key, cache.lookup(key)) { error("failed") }
        }
        assertNull(cache.lookup(key).books)
        assertFailsWith<CancellationException> {
            cache.load(key, cache.lookup(key)) { throw CancellationException("timeout") }
        }
        assertNull(cache.lookup(key).books)
    }

    @Test
    fun successfulEmptyResultsAreCached() = runBlocking {
        val cache = SearchResultCache()
        cache.load(key, cache.lookup(key)) { emptyList() }
        assertEquals(emptyList(), cache.load(key, cache.lookup(key)) { error("empty result missed") })
    }

    @Test
    fun sourceInvalidationClearsEveryUserAndRetainsOtherSources() = runBlocking {
        val cache = SearchResultCache()
        val secondUser = key.copy(userId = 2)
        val otherSource = key.copy(sourceId = 20)
        listOf(key, secondUser, otherSource).forEach { cache.load(it, cache.lookup(it)) { books } }
        cache.invalidateSource(key.sourceId)
        assertNull(cache.lookup(key).books)
        assertNull(cache.lookup(secondUser).books)
        assertEquals(books, cache.lookup(otherSource).books)
    }

    @Test
    fun usersQueriesAndPagesAreIsolated() = runBlocking {
        val cache = SearchResultCache()
        cache.load(key, cache.lookup(key)) { books }
        listOf(key.copy(userId = 2), key.copy(query = "other"), key.copy(page = 2)).forEach {
            assertNull(cache.lookup(it).books)
        }
    }

    @Test
    fun refreshBypassesReadsAndStoresFreshResults() = runBlocking {
        val cache = SearchResultCache()
        cache.load(key, cache.lookup(key)) { books }
        val fresh = books.map { it.copy(name = "fresh") }
        assertEquals(fresh, cache.load(key, cache.lookup(key, refresh = true)) { fresh })
        assertEquals(fresh, cache.lookup(key).books)
    }

    @Test
    fun zeroTtlDisablesReadsAndWrites() = runBlocking {
        val cache = SearchResultCache(Duration.ZERO)
        var calls = 0
        repeat(2) { cache.load(key, cache.lookup(key)) { calls++; books } }
        assertEquals(2, calls)
        assertNull(cache.lookup(key).books)
    }

    @Test
    fun evictsLeastRecentlyUsedEntryAtTotalCap() = runBlocking {
        val cache = SearchResultCache(totalCap = 2)
        val second = key.copy(userId = 2)
        val third = key.copy(userId = 3)
        listOf(key, second).forEach { cache.load(it, cache.lookup(it)) { books } }
        cache.lookup(key)
        cache.load(third, cache.lookup(third)) { books }
        assertNull(cache.lookup(second).books)
        assertEquals(books, cache.lookup(key).books)
        assertEquals(books, cache.lookup(third).books)
    }

    @Test
    fun perUserCapEvictsOnlyThatUsersLeastRecentlyUsedEntry() = runBlocking {
        val cache = SearchResultCache(userCap = 2)
        val second = key.copy(query = "second")
        val third = key.copy(query = "third")
        val otherUser = key.copy(userId = 2)
        listOf(key, second, otherUser).forEach { cache.load(it, cache.lookup(it)) { books } }
        cache.lookup(key)
        cache.load(third, cache.lookup(third)) { books }
        assertNull(cache.lookup(second).books)
        listOf(key, third, otherUser).forEach { assertEquals(books, cache.lookup(it).books) }
    }

    @Test
    fun hitRegistersEveryScriptUrlForCurrentUser() = runBlocking {
        val cache = SearchResultCache()
        val userKey = key.copy(userId = 990100)
        val item = books.single().copy(
            bookUrl = "https://site/{{book}}",
            tocUrl = "https://site/{{toc}}",
            coverUrl = "https://site/{{cover}}",
        )
        cache.load(userKey, cache.lookup(userKey)) { listOf(item) }
        assertFalse(ScriptUrls.isRemembered(userKey.userId, item.bookUrl))
        cache.lookup(userKey)
        listOf(item.bookUrl, item.tocUrl, item.coverUrl!!).forEach {
            assertTrue(ScriptUrls.isRemembered(userKey.userId, it))
            assertFalse(ScriptUrls.isRemembered(userKey.userId + 1, it))
        }
    }

    @Test
    fun invalidationDuringFetchPreventsOldResultsFromRepopulatingCache() = runBlocking {
        val cache = SearchResultCache()
        cache.load(key, cache.lookup(key)) {
            cache.invalidateSource(key.sourceId)
            books
        }
        assertNull(cache.lookup(key).books)
    }
}
