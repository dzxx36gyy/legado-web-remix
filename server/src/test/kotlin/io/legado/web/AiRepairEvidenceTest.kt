package io.legado.web

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.help.engine.Engine
import io.legado.app.utils.GSON
import io.legado.web.auth.UserPrincipal
import io.legado.web.auth.BadRequestException
import io.legado.web.config.DbSettings
import io.legado.web.db.DatabaseFactory
import io.legado.web.service.SettingService
import io.legado.web.service.SourceService
import io.legado.web.service.UserService
import io.legado.web.service.ai.AiModelInput
import io.legado.web.service.ai.AiModelService
import io.legado.web.service.ai.AiRepairService
import io.legado.web.service.ai.RepairRequest
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AiRepairEvidenceTest {
    @Test
    fun discoveryRepairOnlyRetestsAConfiguredCategoryAndLeavesSearchSettingsUntouched() = runBlocking {
        val db = Files.createTempFile("ai-explore-", ".db").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${db.absolutePath}", "", "", 1))
        Engine.secret = "ai-explore-test"
        val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { exchange ->
            exchange.use {
                val path = it.requestURI.path
                paths += path
                val body = when (path) {
                    "/v1/chat/completions" -> GSON.toJson(mapOf("choices" to listOf(mapOf("message" to mapOf(
                        "content" to """{"bookList":"@css:article.book-row"}""",
                    )))))
                    "/category" -> "<article class='book-row'><a href='/book'>Category Book</a></article>"
                    else -> "<p>unexpected request</p>"
                }.toByteArray()
                it.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                it.sendResponseHeaders(200, body.size.toLong())
                it.responseBody.write(body)
            }
        }
        server.start()
        try {
            val settings = SettingService()
            val sources = SourceService(settings, UserService(settings))
            val models = AiModelService()
            val user = UserPrincipal(1, "admin", "admin")
            val source = BookSource(bookSourceUrl = base, bookSourceName = "Explore", searchUrl = "/search?q={{key}}",
                exploreUrl = "分类::/category", concurrentRate = "1000", enabledCookieJar = true).apply {
                ruleSearch = SearchRule(bookList = "@css:.search-row", name = "@css:a@text", bookUrl = "@css:a@href")
            }
            val id = sources.save(user, null, source, false)
            val model = models.save(user, null, AiModelInput("mock", "$base/v1", "mock", "fixture-key"))
            val repair = AiRepairService(sources, models)
            val request = RepairRequest(id, model.id, exploreUrl = "/category")
            assertFailsWith<BadRequestException> { repair.repair(user, request.copy(exploreUrl = "/not-configured")) {} }
            assertTrue(paths.isEmpty())
            val events = mutableListOf<JsonObject>()
            repair.repair(user, request) { events += GSON.toJsonTree(it).asJsonObject }
            assertEquals(listOf("/category", "/v1/chat/completions", "/category"), paths.toList())
            assertTrue(events.filter { it["type"].asString == "progress" }.all { it["stage"].asString == "explore" })
            val result = events.single { it["type"].asString == "result" }
            assertTrue(result["diff"].asJsonArray.all { it.asJsonObject["field"].asString.startsWith("ruleExplore.") })
            val candidate = GSON.fromJson(result["source"].asString, BookSource::class.java)
            assertEquals("@css:article.book-row", candidate.ruleExplore?.bookList)
            assertEquals(source.ruleSearch?.name, candidate.ruleExplore?.name)
            assertEquals(source.ruleSearch, candidate.ruleSearch)
            assertEquals(source.searchUrl, candidate.searchUrl)
            assertEquals(source.exploreUrl, candidate.exploreUrl)
            assertEquals("1000", candidate.concurrentRate)
            assertEquals(true, candidate.enabledCookieJar)
            assertEquals(null, sources.get(user, id).source.ruleExplore)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun repairUsesRedactedRedirectEvidenceAndCanDeclineWithoutRetryingOrSaving() = runBlocking {
        val db = Files.createTempFile("ai-evidence-", ".db").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${db.absolutePath}", "", "", 1))
        Engine.secret = "ai-evidence-test"
        val prompts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { exchange ->
            exchange.use {
                val path = it.requestURI.path
                if (path == "/search") {
                    it.responseHeaders.add("Location", if (it.requestURI.query.contains("Empty")) "/empty?private=redirect-witness" else "/")
                    it.sendResponseHeaders(302, -1)
                } else {
                    val body = when (path) {
                        "/v1/chat/completions" -> {
                            val request = JsonParser.parseString(it.requestBody.bufferedReader().use { r -> r.readText() }).asJsonObject
                            prompts += request["messages"].asJsonArray.last().asJsonObject["content"].asString
                            GSON.toJson(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to """{"noPatch":"noResults"}""")))))
                        }
                        "/empty" -> "<main><p>No results for Empty</p><form><input name='password' value='credential-witness'></form></main>"
                        else -> "<main><aside class='recommendations'><a href='/book/one'>First Story</a></aside></main>"
                    }.toByteArray()
                    it.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                    it.sendResponseHeaders(200, body.size.toLong())
                    it.responseBody.write(body)
                }
            }
        }
        server.start()
        try {
            val settings = SettingService()
            val sources = SourceService(settings, UserService(settings))
            val models = AiModelService()
            val user = UserPrincipal(1, "admin", "admin")
            val source = BookSource(bookSourceUrl = base, bookSourceName = "Evidence", searchUrl = "/search?q={{key}}").apply {
                ruleSearch = SearchRule(bookList = "@css:aside.recommendations", name = "@css:a@text", bookUrl = "@css:a@href")
            }
            val id = sources.save(user, null, source, false)
            val model = models.save(user, null, AiModelInput("mock", "$base/v1", "mock", "fake-model-key"))
            val repair = AiRepairService(sources, models)
            val events = mutableListOf<JsonObject>()
            repair.repair(user, RepairRequest(id, model.id, "First Story")) { events += GSON.toJsonTree(it).asJsonObject }
            assertTrue(events.none { it["type"].asString in setOf("result", "awaiting-confirmation") })
            assertEquals(0, prompts.size, "a search redirected to a bare home page should not be patched")
            assertEquals("search-landing", events.single { it["type"].asString == "detect-failed" }["code"].asString)

            events.clear()
            repair.repair(user, RepairRequest(id, model.id, "Empty")) { events += GSON.toJsonTree(it).asJsonObject }
            assertTrue(events.none { it["type"].asString in setOf("result", "awaiting-confirmation") })
            assertEquals("noResults", events.single { it["type"].asString == "detect-failed" }["reason"].asString)
            assertEquals("end", events.last()["type"].asString)
            val prompt = prompts.single()
            val evidence = JsonParser.parseString(prompt.substring(prompt.indexOf('\n' + "{")).trim()).asJsonObject
            val request = evidence["requests"].asJsonArray.single().asJsonObject
            assertEquals("$base/search", request["requestedUrl"].asString)
            assertEquals("$base/empty", request["url"].asString)
            assertEquals(302, request["redirectStatuses"].asJsonArray.single().asInt)
            assertEquals(0, evidence["hits"].asJsonObject["search.book"].asInt)
            assertFalse(prompt.contains("redirect-witness") || prompt.contains("credential-witness") || prompt.contains("fake-model-key"))
            assertEquals("@css:aside.recommendations", sources.get(user, id).source.ruleSearch?.bookList)
        } finally {
            server.stop(0)
        }
    }
}
