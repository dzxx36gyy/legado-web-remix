package io.legado.web

import io.legado.web.config.DbSettings
import io.legado.web.db.DatabaseFactory
import io.legado.web.db.DbEngineStore
import io.legado.web.db.EngineCaches
import io.legado.web.db.dbBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DbEngineStoreTest {
    @Test
    fun memoryEvictsLeastRecentlyUsedEntryAndKeepsLargeValuesOnlyInDatabase() {
        val file = Files.createTempFile("engine-cache-", ".db").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${file.absolutePath}", "", "", 1))
        val store = DbEngineStore()
        for (index in 0 until 5000) store.putCache(1, "key$index", "value$index", 0)
        assertEquals("value0", store.getCache(1, "key0"))
        store.putCache(1, "overflow", "new", 0)
        // 删除持久层副本，使后续读取可区分内存命中和数据库回读。
        dbBlocking { EngineCaches.deleteWhere { EngineCaches.userId eq 1L } }
        assertEquals("value0", store.getCache(1, "key0"))
        assertNull(store.getCache(1, "key1"))
        assertEquals("new", store.getCache(1, "overflow"))

        for (size in listOf(65536, 65537)) {
            val key = "size$size"
            val value = "x".repeat(size)
            store.putCache(2, key, value, 0)
            assertEquals(value, DbEngineStore().getCache(2, key))
            assertEquals(value, store.getCache(2, key))
            dbBlocking { EngineCaches.deleteWhere { EngineCaches.userId eq 2L } }
            assertEquals(if (size == 65536) value else null, store.getCache(2, key))
        }
    }
}
