package io.legado.web.webview

import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class BrowserConnection(private val address: () -> String?, private val contexts: BrowserContexts) : AutoCloseable {
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicInteger()
    @Volatile private var connection: CdpWebViewProvider.CdpConnection? = null
    @Volatile private var connectedTo: String? = null
    private var idleJob: kotlinx.coroutines.Job? = null

    internal suspend fun acquire(): Pair<CdpWebViewProvider.CdpConnection, String> = lock.withLock {
        val addr = address() ?: throw io.legado.app.exception.NoStackTraceException("没有设置浏览器")
        val existing = connection?.takeIf { it.isOpen && connectedTo == addr }
        val conn = existing ?: run {
            connection?.close()
            val (url, host) = withContext(Dispatchers.IO) { RemoteCdp.resolve(addr) }
            val created = CdpWebViewProvider.CdpConnection(url, host)
            try {
                created.awaitOpen()
                contexts.cleanup(addr, created)
                created.send("Target.setDiscoverTargets", JsonObject().apply { addProperty("discover", true) })
            } catch (cause: Exception) {
                created.close()
                throw cause
            }
            connection = created
            connectedTo = addr
            created
        }
        idleJob?.cancel()
        active.incrementAndGet()
        conn to addr
    }

    fun checkReachable(): String? = try {
        val addr = address() ?: throw io.legado.app.exception.NoStackTraceException("没有设置浏览器")
        if (connection?.isOpen != true || connectedTo != addr) RemoteCdp.ping(addr)
        null
    } catch (cause: Exception) { cause.message ?: cause.javaClass.simpleName }

    fun release() {
        if (active.decrementAndGet() != 0) return
        idleJob = scope.launch {
            delay(5 * 60_000L)
            lock.withLock {
                if (active.get() == 0) {
                    connection?.close()
                    connection = null
                    connectedTo = null
                }
            }
        }
    }

    override fun close() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        connection?.close()
    }
}
