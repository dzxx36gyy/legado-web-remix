package io.legado.web

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import io.legado.web.service.tts.EdgeSpeechStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import kotlin.test.*

class EdgeSpeechStreamTest {
    @Test
    fun slowConsumerReceivesEveryChunkAndCancellationReleasesBlockedCallback() = runBlocking {
        val burstSent = CompletableDeferred<Unit>()
        val firstReceived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Unit>()
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/speech") {
                    incoming.receive()
                    incoming.receive()
                    try {
                        repeat(10000) { index ->
                            val header = "Path:audio\r\nContent-Type:audio/mpeg\r\n".toByteArray()
                            val bytes = ByteBuffer.allocate(2 + header.size + 4)
                                .putShort(header.size.toShort()).put(header).putInt(index).array()
                            send(Frame.Binary(true, bytes))
                        }
                        burstSent.complete(Unit)
                        send("Path:turn.end\r\n\r\n")
                        for (frame in incoming) { if (frame is Frame.Close) break }
                    } finally { disconnected.complete(Unit) }
                }
            }
        }.start(false)
        val client = OkHttpClient()
        try {
            val port = server.engine.resolvedConnectors().single().port
            val stream = EdgeSpeechStream(client, Request.Builder().url("http://127.0.0.1:$port/speech").build()) { error, _ -> error }
            val values = mutableListOf<Int>()
            val playback = async(Dispatchers.IO) {
                stream.stream("config", "ssml").collect { bytes ->
                    firstReceived.complete(Unit)
                    release.await()
                    values.add(ByteBuffer.wrap(bytes).int)
                }
            }
            withTimeout(5000) { firstReceived.await(); burstSent.await() }
            release.complete(Unit)
            withTimeout(5000) { playback.await() }
            assertEquals((0 until 10000).toList(), values)
            withTimeout(5000) { disconnected.await() }

            val waiting = CompletableDeferred<Unit>()
            val cancelled = launch(Dispatchers.IO) {
                stream.stream("config", "ssml").collect { waiting.complete(Unit); awaitCancellation() }
            }
            withTimeout(5000) { waiting.await() }
            withTimeout(5000) { cancelled.cancelAndJoin() }
            assertTrue(cancelled.isCancelled)
        } finally {
            server.stop(0, 1000)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
