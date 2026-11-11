package io.legado.web

import com.sun.net.httpserver.HttpServer
import io.legado.app.utils.GSON
import io.legado.web.auth.BadRequestException
import io.legado.web.service.ai.AiChatClient
import io.legado.web.service.ai.AiModelInput
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiChatClientTest {
    @Test
    fun completionRejectsEmptyReasoningOnlyAndTruncatedResults() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var choice: Map<String, Any?> = emptyMap()
        server.createContext("/chat/completions") { exchange ->
            val body = GSON.toJson(mapOf("choices" to listOf(choice))).toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            val client = AiChatClient()
            val config = AiModelInput(baseUrl = "http://127.0.0.1:${server.address.port}", model = "test", apiKey = "test-key")
            listOf(
                mapOf("message" to mapOf("content" to "")) to "AI 模型返回内容为空",
                mapOf("message" to mapOf("content" to null, "reasoning_content" to "thinking")) to "模型只返回了推理内容，没有给出结果（可调大最大令牌数或换非推理模型）",
                mapOf("message" to mapOf("content" to "partial"), "finish_reason" to "length") to "AI 模型输出被最大令牌数截断，请调大最大令牌数",
            ).forEach { (response, expected) ->
                choice = response
                assertEquals(expected, assertFailsWith<BadRequestException> { client.complete(config, "test") }.message)
            }
            choice = mapOf("message" to mapOf("content" to "OK"), "finish_reason" to "stop")
            assertEquals("OK", client.complete(config, "test"))
        } finally { server.stop(0) }
    }

    @Test
    fun httpErrorsExposeStatusAndBoundedRedactedProviderReason() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val key = "private-provider-key"
        server.createContext("/chat/completions") { exchange ->
            val body = """{"error":"quota exhausted", "api_key":"$key", "Authorization":"Bearer other-secret", "extra":"${"x".repeat(300)}"}""".toByteArray()
            exchange.sendResponseHeaders(429, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            val error = assertFailsWith<BadRequestException> {
                AiChatClient().complete(AiModelInput(baseUrl = "http://127.0.0.1:${server.address.port}", apiKey = key), "test")
            }.message.orEmpty()
            assertTrue(error.contains("429"))
            assertTrue(error.contains("quota exhausted"))
            assertFalse(error.contains(key))
            assertFalse(error.contains("other-secret"))
            assertTrue(error.length <= 220)
        } finally { server.stop(0) }
    }
}
