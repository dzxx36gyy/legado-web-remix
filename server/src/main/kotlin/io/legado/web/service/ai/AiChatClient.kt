package io.legado.web.service.ai

import com.google.gson.JsonParser
import io.legado.app.utils.GSON
import io.legado.web.auth.BadRequestException
import io.legado.web.security.NetworkGuard
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AiChatClient {
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .socketFactory(NetworkGuard.socketFactory)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

    suspend fun complete(config: AiModelInput, prompt: String): String {
        val payload = mutableMapOf<String, Any>(
            "model" to config.model,
            "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
            "stream" to false,
        )
        config.temperature?.let { payload["temperature"] = it }
        config.maxTokens?.let { payload["max_tokens"] = it }
        val request = Request.Builder().url(config.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(GSON.toJson(payload).toRequestBody("application/json".toMediaType())).build()
        val text = suspendCancellableCoroutine<String> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(BadRequestException("AI 模型请求失败"))
                }
                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    try {
                        val body = response.use {
                            if (!it.isSuccessful) throw BadRequestException("AI 模型 HTTP ${it.code}")
                            val bytes = it.body.byteStream().use { stream -> stream.readNBytes(262145) }
                            if (bytes.size > 262144) throw BadRequestException("AI 模型响应过大")
                            String(bytes, Charsets.UTF_8)
                        }
                        if (continuation.isActive) continuation.resume(body)
                    } catch (e: BadRequestException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(BadRequestException("AI 模型响应读取失败"))
                    }
                }
            })
        }
        try {
            return JsonParser.parseString(text).asJsonObject["choices"].asJsonArray[0].asJsonObject["message"]
                .asJsonObject["content"].asString
        } catch (e: RuntimeException) {
            throw BadRequestException("AI 模型响应格式错误")
        }
    }
}
