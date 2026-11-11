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
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(200, TimeUnit.SECONDS)
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
        val text = execute(request, config.apiKey)
        try {
            val choice = JsonParser.parseString(text).asJsonObject["choices"].asJsonArray[0].asJsonObject
            val message = choice["message"].asJsonObject
            val content = message["content"]?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            if (choice["finish_reason"]?.takeUnless { it.isJsonNull }?.asString == "length") {
                val reasoningOnly = content.isBlank() && !message["reasoning_content"]?.takeUnless { it.isJsonNull }?.asString.isNullOrBlank()
                throw BadRequestException(if (reasoningOnly) "模型只返回了推理内容，没有给出结果（可调大最大令牌数或换非推理模型）；输出被最大令牌数截断" else "AI 模型输出被最大令牌数截断，请调大最大令牌数")
            }
            if (content.isBlank()) {
                if (!message["reasoning_content"]?.takeUnless { it.isJsonNull }?.asString.isNullOrBlank()) {
                    throw BadRequestException("模型只返回了推理内容，没有给出结果（可调大最大令牌数或换非推理模型）")
                }
                throw BadRequestException("AI 模型返回内容为空")
            }
            return content
        } catch (e: BadRequestException) { throw e
        } catch (e: RuntimeException) { throw BadRequestException("AI 模型响应格式错误") }
    }

    suspend fun fetchModels(baseUrl: String, apiKey: String): List<String> {
        val request = Request.Builder().url(baseUrl + "/models").header("Authorization", "Bearer $apiKey").build()
        return parseModels(execute(request, apiKey))
    }

    internal fun parseModels(text: String): List<String> {
        try {
            val data = JsonParser.parseString(text).asJsonObject["data"].asJsonArray
            return data.map { node ->
                val id = node.asJsonObject["id"]
                if (id == null || !id.isJsonPrimitive || !id.asJsonPrimitive.isString || id.asString.isBlank()) {
                    throw BadRequestException("AI 模型列表格式错误")
                }
                id.asString
            }.distinct().sorted().take(2000)
        } catch (e: BadRequestException) { throw e
        } catch (e: RuntimeException) { throw BadRequestException("AI 模型列表格式错误") }
    }

    private suspend fun execute(request: Request, apiKey: String): String = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(BadRequestException(
                        if (e is java.io.InterruptedIOException) "AI 模型请求超时" else "AI 模型请求失败"))
                }
                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    try {
                        val body = response.use {
                            val bytes = it.body.byteStream().use { stream -> stream.readNBytes(262145) }
                            if (!it.isSuccessful) {
                                val raw = String(bytes, Charsets.UTF_8).let { body -> if (apiKey.isNotEmpty()) body.replace(apiKey, "[REDACTED]") else body }
                                val snippet = AiRepairPayload.redact(raw).replace(Regex("[\\r\\n]+"), " ").take(200)
                                throw BadRequestException("AI 模型 HTTP ${it.code}：$snippet")
                            }
                            if (bytes.size > 262144) throw BadRequestException("AI 模型响应过大")
                            String(bytes, Charsets.UTF_8)
                        }
                        if (continuation.isActive) continuation.resume(body)
                    } catch (e: BadRequestException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    } catch (e: java.io.InterruptedIOException) {
                        if (continuation.isActive) continuation.resumeWithException(BadRequestException("AI 模型请求超时"))
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(BadRequestException("AI 模型响应读取失败"))
                    }
                }
            })
    }
}
