package io.legado.web.service.tts

import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.EOFException
import java.net.SocketException

internal class SpeechConnectionInterrupted(message: String) : NoStackTraceException(message)

internal class EdgeSpeechStream(
    private val client: OkHttpClient,
    private val request: Request,
    private val failure: (Throwable, Response?) -> Throwable,
) {
    fun stream(config: String, ssml: String) = callbackFlow {
        var total = 0
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            private var opened = false

            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                webSocket.send(config)
                webSocket.send(ssml)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.substringBefore("\r\n\r\n").lineSequence().any { it.trim() == "Path:turn.end" }) {
                    webSocket.close(1000, null)
                    if (total == 0) close(NoStackTraceException("Edge 朗读没有返回音频")) else close()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size < 2) return
                val headerLen = ((data[0].toInt() and 255) shl 8) or (data[1].toInt() and 255)
                if (headerLen + 2 > data.size) return
                val head = String(data, 2, headerLen, Charsets.US_ASCII)
                if (head.lines().none { it.trim() == "Path:audio" }) return
                if (head.lines().none { it.trim().equals("Content-Type:audio/mpeg", true) }) return
                val audio = data.copyOfRange(2 + headerLen, data.size)
                total += audio.size
                if (total > 10 * 1024 * 1024) {
                    close(NoStackTraceException("Edge 朗读返回的音频过大：$total 字节"))
                    webSocket.cancel()
                } else {
                    // Backpressure belongs on OkHttp's reader thread; cancellation closes the channel and releases it.
                    if (trySendBlocking(audio).isFailure) webSocket.cancel()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error = failure(t, response)
                close(if (opened && (t is EOFException || t is SocketException))
                    SpeechConnectionInterrupted(error.message.orEmpty()) else error)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close(SpeechConnectionInterrupted("Edge 朗读连接意外关闭（$code）"))
            }
        })
        awaitClose { ws.cancel() }
    }.buffer(32)
}
