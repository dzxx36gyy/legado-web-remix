package io.legado.web.service.tts

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resumeWithException

internal object TtsHttpStream {
    suspend fun read(request: Request, consume: suspend (Response) -> Unit) = coroutineScope {
        val call = EdgeTts.client.newBuilder().followRedirects(false).followSslRedirects(false)
            .callTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build().newCall(request)
        val response = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) { cont.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response) { _, value, _ -> value.close() }
                }
            })
        }
        val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel(); response.close() }
        }
        try { withContext(Dispatchers.IO) { response.use { consume(it) } } }
        finally { cancellation.cancel() }
    }
}
