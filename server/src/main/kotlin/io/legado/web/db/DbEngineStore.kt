package io.legado.web.db

import io.legado.app.help.engine.EngineStore
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.concurrent.ConcurrentHashMap

/**
 * 基于数据库的引擎存储，带进程内缓存（书源脚本会高频读写变量）
 */
class DbEngineStore : EngineStore {

    private data class Entry(val value: String?, val deadline: Long)

    private val cache: MutableMap<String, Entry> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 5000
        }
    )
    private val cookies = ConcurrentHashMap<String, String>()

    private fun k(userId: Long, key: String) = "$userId|$key"

    override fun getCache(userId: Long, key: String): String? {
        val entry = cache[k(userId, key)] ?: run {
            dbBlocking {
                EngineCaches.selectAll()
                    .where { (EngineCaches.userId eq userId) and (EngineCaches.key eq key) }
                    .firstOrNull()
                    ?.let { Entry(it[EngineCaches.value], it[EngineCaches.deadline]) }
            } ?: Entry(null, 0)
        }.also {
            if ((it.value?.length ?: 0) <= 65536) cache[k(userId, key)] = it
        }
        if (entry.value == null) return null
        if (entry.deadline != 0L && entry.deadline < System.currentTimeMillis()) {
            deleteCache(userId, key)
            return null
        }
        return entry.value
    }

    override fun putCache(userId: Long, key: String, value: String, deadline: Long) {
        require(key.length <= 512) { "缓存 key 过长" }
        if (value.length <= 65536) cache[k(userId, key)] = Entry(value, deadline) else cache.remove(k(userId, key))
        dbBlocking {
            EngineCaches.upsert {
                it[EngineCaches.userId] = userId
                it[EngineCaches.key] = key
                it[EngineCaches.value] = value
                it[EngineCaches.deadline] = deadline
            }
        }
    }

    override fun deleteCache(userId: Long, key: String) {
        cache[k(userId, key)] = Entry(null, 0)
        dbBlocking {
            EngineCaches.deleteWhere { (EngineCaches.userId eq userId) and (EngineCaches.key eq key) }
        }
    }

    override fun getCookie(userId: Long, domain: String): String? {
        val kk = k(userId, domain)
        cookies[kk]?.let { return it.ifEmpty { null } }
        val v = dbBlocking {
            Cookies.selectAll()
                .where { (Cookies.userId eq userId) and (Cookies.domain eq domain) }
                .firstOrNull()?.get(Cookies.cookie)
        }
        cookies[kk] = v ?: ""
        return v
    }

    override fun putCookie(userId: Long, domain: String, cookie: String) {
        cookies[k(userId, domain)] = cookie
        dbBlocking {
            Cookies.upsert {
                it[Cookies.userId] = userId
                it[Cookies.domain] = domain
                it[Cookies.cookie] = cookie
            }
        }
    }

    override fun deleteCookie(userId: Long, domain: String) {
        cookies[k(userId, domain)] = ""
        dbBlocking {
            Cookies.deleteWhere { (Cookies.userId eq userId) and (Cookies.domain eq domain) }
        }
    }

    /** 清除某用户与指定前缀相关的内存缓存（例如删除书源后） */
    fun evictUser(userId: Long) {
        val prefix = "$userId|"
        synchronized(cache) { cache.keys.removeIf { it.startsWith(prefix) } }
        cookies.keys.removeIf { it.startsWith(prefix) }
    }

    /** 清理过期缓存 */
    fun cleanExpired() {
        val now = System.currentTimeMillis()
        dbBlocking {
            EngineCaches.deleteWhere { (EngineCaches.deadline greater 0L) and (EngineCaches.deadline less now) }
        }
        synchronized(cache) { cache.entries.removeIf { it.value.deadline != 0L && it.value.deadline < now } }
    }

    fun deleteUserData(userId: Long) {
        dbBlocking {
            EngineCaches.deleteWhere { EngineCaches.userId eq userId }
            Cookies.deleteWhere { Cookies.userId eq userId }
        }
        evictUser(userId)
    }
}
