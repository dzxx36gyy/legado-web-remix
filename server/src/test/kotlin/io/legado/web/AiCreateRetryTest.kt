package io.legado.web

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import io.legado.app.help.engine.Engine
import io.legado.app.utils.GSON
import io.legado.web.auth.UserPrincipal
import io.legado.web.config.DbSettings
import io.legado.web.db.DatabaseFactory
import io.legado.web.service.SettingService
import io.legado.web.service.SourceService
import io.legado.web.service.UserService
import io.legado.web.service.ai.*
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiCreateRetryTest {
    @Test
    fun emptySearchPatchReceivesFeedbackAndCreatesValidatedCandidate() = runBlocking {
        val db = Files.createTempFile("ai-create-retry-", ".db").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${db.absolutePath}", "", "", 1))
        Engine.secret = "ai-create-retry"
        val prompts = mutableListOf<JsonObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { exchange ->
            val body = when (exchange.requestURI.path) {
                "/v1/chat/completions" -> {
                    val prompt = JsonParser.parseString(exchange.requestBody.bufferedReader().readText()).asJsonObject
                        .getAsJsonArray("messages")[0].asJsonObject["content"].asString
                    val data = prompt.lineSequence().dropWhile { !it.trimStart().startsWith("{") }.joinToString("\n")
                    val payload = data.takeIf { it.isNotBlank() }?.let { JsonParser.parseString(it).asJsonObject }
                    val patch = if (payload == null) {
                        """{"searchUrl":"/search?q={{key}}","ruleSearch":{"bookList":".missing","name":"a@text","bookUrl":"a@href"}}"""
                    } else {
                        prompts += payload
                        when (payload["stage"].asString) {
                            "search" -> if (prompts.size == 1) "{}" else """{"bookList":".book"}"""
                            "bookInfo" -> """{"name":"h1@text","tocUrl":"a@href"}"""
                            "toc" -> """{"chapterList":".chapter","chapterName":"a@text","chapterUrl":"a@href"}"""
                            else -> """{"content":".content@text"}"""
                        }
                    }
                    GSON.toJson(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to patch)))))
                }
                "/search" -> "<div class='book'><a href='/book'>Book</a></div>"
                "/book" -> "<h1>Book</h1><a href='/toc'>toc</a><div class='chapter'><a href='/content'>Chapter</a></div>"
                "/toc" -> "<div class='chapter'><a href='/content'>Chapter</a></div>"
                "/content" -> "<div class='content'>Chapter content with enough text.</div>"
                else -> "<form action='/search'><input name='q'></form>"
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            val settings = SettingService()
            val sources = SourceService(settings, UserService(settings))
            val models = AiModelService()
            val user = UserPrincipal(1, "admin", "admin")
            val model = models.save(user, null, AiModelInput("mock", "$base/v1", "mock", "secret"))
            val events = mutableListOf<JsonObject>()
            AiRepairService(sources, models).create(user, CreateSourceRequest(base, "Book", model.id)) {
                events += GSON.toJsonTree(it).asJsonObject
            }
            assertEquals("invalid", prompts[1].getAsJsonObject("previousPatch")["status"].asString)
            assertEquals("search", prompts[1]["stage"].asString)
            assertTrue(prompts[1]["page"].asString.contains("class=\"book\""))
            val result = events.single { it["type"].asString == "result" }
            assertEquals(".book", JsonParser.parseString(result["source"].asString).asJsonObject.getAsJsonObject("ruleSearch")["bookList"].asString)
            assertTrue(events.any { it["type"].asString == "evidence" && it.getAsJsonObject("evidence")["stage"] == null })
        } finally { server.stop(0) }
    }
}
