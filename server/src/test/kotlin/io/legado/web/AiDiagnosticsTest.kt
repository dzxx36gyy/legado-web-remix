package io.legado.web

import io.legado.app.data.entities.BookSource
import io.legado.web.service.ai.AiRepairPayload
import io.legado.web.service.ai.RepairStage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiDiagnosticsTest {
    @Test
    fun syntaxErrorsAreReportedWithoutExecutingScripts() {
        val source = AiRepairPayload.patch(RepairStage.search, BookSource(),
            """{"bookList":"@css:div[", "name":"$[", "author":"@js:throw new Error('must not execute')"}""")
        val result = AiRepairPayload.syntaxChecks(source)
        assertTrue(result.getValue("ruleSearch.bookList").startsWith("CSS:"))
        assertTrue(result.getValue("ruleSearch.name").startsWith("JSONPath:"))
        assertTrue(result.getValue("ruleSearch.author").contains("validated by engine"))
    }

    @Test
    fun searchFormStructureSurvivesButSecretsDoNot() {
        val page = AiRepairPayload.excerpt("""<form action="/search"><input name="q" value="private"><input type="hidden" name="token" value="secret"><input type="password" value="password"></form>""", preserveForms = true)
        assertTrue(page.contains("/search"))
        assertTrue(page.contains("name=\"q\""))
        assertFalse(page.contains("private"))
        assertFalse(page.contains("secret"))
        assertFalse(page.contains("password"))
    }
}
