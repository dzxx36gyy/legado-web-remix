package io.legado.web.webview

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class BrowserTaskQueue {
    private val slot = Semaphore(1, true)
    private val waiting = CopyOnWriteArrayList<String>()

    fun acquire(token: String, cancelled: () -> Boolean): Boolean {
        waiting.add(token)
        try {
            while (!cancelled()) {
                if (slot.tryAcquire(100, TimeUnit.MILLISECONDS)) return true
            }
            return false
        } finally {
            waiting.remove(token)
        }
    }

    fun release() = slot.release()
    fun position(token: String) = waiting.indexOf(token) + 1
    fun isIdle() = slot.availablePermits() > 0 && waiting.isEmpty()
}
