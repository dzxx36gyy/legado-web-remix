package io.legado.web

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.help.engine.Engine
import io.legado.app.utils.GSON
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.UserPrincipal
import io.legado.web.config.DbSettings
import io.legado.web.db.AiModels
import io.legado.web.db.DatabaseFactory
import io.legado.web.service.ai.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiRepairTest {
    @Test
    fun patchAcceptsFencesAndProseWithoutRelaxingStageRules() {
        val source = BookSource().apply { ruleSearch = SearchRule(bookList = ".broken") }
        listOf(
            "```json\n{\"bookList\":\".book\"}\n```",
            "```\n{\"bookList\":\".book\"}\n```",
            "以下是补丁：\n{\"bookList\":\".book\"}\n请重新检测。",
            "{\"bookList\":\".book\",\"name\":\"a[data-x=\\\"}\\\"]@text\"}",
        ).forEach { response ->
            assertEquals(".book", AiRepairPayload.patch(RepairStage.search, source, response).ruleSearch?.bookList)
        }
        listOf("```json\n{\"chapterList\":\"a\"}\n```", "补丁 {\"bookList\":42} 结束", "```json\n{\"bookList\":\"a\",}\n```").forEach { response ->
            assertFailsWith<BadRequestException> { AiRepairPayload.patch(RepairStage.search, source, response) }
        }
    }

    @Test
    fun fetchModelsRejectsPrivateHostsBeforeLookingUpCredentials() = runBlocking {
        io.legado.web.security.NetworkGuard.configure { "" }
        try {
            assertFailsWith<BadRequestException> {
                AiModelService().fetchModels(UserPrincipal(1, "a", "admin"), FetchModelsRequest("http://127.0.0.1/v1", id = 123))
            }
        } finally { io.legado.web.security.NetworkGuard.resetForTests() }
    }

    @Test
    fun modelListParsesSortedUniqueStringIdsAndCapsResults() {
        val client = AiChatClient()
        assertEquals(listOf("a", "z"), client.parseModels("""{"data":[{"id":"z"},{"id":"a"},{"id":"z"}]}"""))
        assertEquals(2000, client.parseModels(GSON.toJson(mapOf("data" to (0..2000).map { mapOf("id" to "model-$it") }))).size)
        listOf("{}", "{\"data\":[{\"id\":12}]}", "{\"data\":[{\"id\":\"\"}]}").forEach {
            assertFailsWith<BadRequestException> { client.parseModels(it) }
        }
    }

    @Test
    fun creatorAloneCanListUseUpdateAndDeleteModels() = runBlocking {
        val file = Files.createTempFile("ai-model-", ".db").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:${file.absolutePath}", "", "", 1))
        Engine.secret = "test-secret"
        val service = AiModelService()
        val a = UserPrincipal(1, "a", "admin")
        val b = UserPrincipal(2, "b", "admin")
        val input = AiModelInput("mock", "https://example.com/v1", "test", "private-key-marker")
        val model = service.save(a, null, input)
        assertEquals(listOf(model.id), service.list(a).map { it.id })
        assertEquals(emptyList(), service.list(b))
        assertFailsWith<NotFoundException> { service.usable(b, model.id) }
        assertFailsWith<NotFoundException> { service.save(b, model.id, input) }
        assertFailsWith<NotFoundException> { service.delete(b, model.id) }
        assertFalse(GSON.toJson(model).contains(input.apiKey))
        transaction(DatabaseFactory.database) {
            assertFalse(AiModels.selectAll().single()[AiModels.json].contains(input.apiKey))
        }
        service.save(a, model.id, input.copy(apiKey = ""))
        assertEquals(input.apiKey, service.usable(a, model.id).apiKey)
        service.delete(a, model.id)
        assertEquals(emptyList(), service.list(a))
    }

    @Test
    fun patchRejectsOtherStagesAndMalformedOrAmbiguousJson() {
        val source = BookSource().apply { ruleSearch = SearchRule(bookList = ".broken") }
        listOf(
            "{", "[]", "[{\"bookList\":\"a\"}]", "{}", "{\"ruleToc\":{\"chapterList\":\"a\"}}",
            "{\"chapterList\":\"a\"}", "{\"bookList\":42}",
            "{\"bookList\":\"a\",\"bookList\":\"b\"}", "{\"bookList\":\"a\"} {}",
        ).forEach { response ->
            assertFailsWith<BadRequestException>(response) { AiRepairPayload.patch(RepairStage.search, source, response) }
        }
        val patched = AiRepairPayload.patch(RepairStage.search, source, "{\"bookList\":\".book\"}")
        assertEquals(".book", patched.ruleSearch?.bookList)
        assertEquals(".broken", source.ruleSearch?.bookList)
        assertEquals(listOf("ruleSearch.bookList"), AiRepairPayload.diff(source, patched).map { it.field })
    }

    @Test
    fun promptExcludesCredentialsAndCapsCleanedPage() {
        val source = BookSource().apply {
            header = "{\"Cookie\":\"source-secret\"}"
            loginUrl = "https://example.com/login?password=login-secret"
            ruleSearch = SearchRule(bookList = ".book", bookUrl = "a@href,{\"headers\":{\"Authorization\":\"Bearer rule-secret\"}}")
        }
        val page = "<script>script-secret</script><style>style-secret</style><form>form-secret</form><div>" + "a".repeat(40000) + "</div>"
        val prompt = AiRepairPayload.prompt(RepairStage.search, source, RepairEvidence(RepairStage.search, "parse failed", page))
        listOf("source-secret", "login-secret", "rule-secret", "script-secret", "style-secret", "form-secret").forEach {
            assertFalse(prompt.contains(it), it)
        }
        assertEquals(AiRepairPayload.PAGE_LIMIT, AiRepairPayload.excerpt(page).length)
        assertTrue(prompt.length <= AiRepairPayload.PROMPT_LIMIT)
        val json = AiRepairPayload.excerpt("""{"data":{"name":"book","cookie":"json-secret"},"authorization":"auth-secret"}""")
        assertFalse(json.contains("json-secret"))
        assertFalse(json.contains("auth-secret"))
        assertTrue(json.contains("book"))
    }

    @Test
    fun oversizedRulesFailBeforeBeingSentAndJavascriptIsFlagged() {
        val source = BookSource().apply { ruleSearch = SearchRule(bookList = "a".repeat(60000)) }
        assertFailsWith<BadRequestException> {
            AiRepairPayload.prompt(RepairStage.search, source, RepairEvidence(RepairStage.search, "error", "page"))
        }
        val patched = AiRepairPayload.patch(RepairStage.search, source, """{"bookList":"@js:result"}""")
        assertTrue(AiRepairPayload.diff(source, patched).single().javascript)
    }
}
