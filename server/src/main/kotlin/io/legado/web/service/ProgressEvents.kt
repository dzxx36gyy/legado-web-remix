package io.legado.web.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class ProgressEvent(val bookId: Long, val index: Int, val pos: Int, val title: String?, val time: Long, val client: String?)

object ProgressEvents {
    const val PREF_KEY = "progressSync"
    class Subscription(val userId: Long, val client: String?) {
        val channel = Channel<ProgressEvent>(16, BufferOverflow.DROP_OLDEST)
    }
    private val subscribers = ConcurrentHashMap<Long, CopyOnWriteArrayList<Subscription>>()
    @Volatile var loadUserEnabled: (Long) -> Boolean = { false }
    private val gson = com.google.gson.Gson()
    fun toJson(event: ProgressEvent): String = gson.toJson(event)
    fun subscribe(userId: Long, client: String?): Subscription = Subscription(userId, client).also { sub ->
        subscribers.compute(userId) { _, list -> (list ?: CopyOnWriteArrayList()).apply { add(sub) } }
    }
    fun unsubscribe(sub: Subscription) {
        subscribers.computeIfPresent(sub.userId) { _, list -> list.apply { remove(sub) }.takeIf { it.isNotEmpty() } }
        sub.channel.close()
    }
    fun publish(userId: Long, event: ProgressEvent) {
        if (!loadUserEnabled(userId)) return
        subscribers[userId]?.forEach { if (event.client == null || it.client != event.client) it.channel.trySend(event) }
    }
}
