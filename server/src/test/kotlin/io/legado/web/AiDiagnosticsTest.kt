package io.legado.web

import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.web.service.ai.AiRepairPayload
import io.legado.web.service.ai.RepairStage
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiDiagnosticsTest {
    @Test
    fun cssAlternativesSelectObservedRowsAndFallBackForMissingAttributes() {
        val html = checkNotNull(javaClass.getResource("/ai-repair/search-variants.html")).readText()
        val doc = Jsoup.parse(html)
        val list = "@css:article.book-card, article.book-row||article.result-row"
        val expected = mapOf("results-primary" to listOf(
            "/book/one" to "/covers/one.webp", "/book/two" to "/covers/two.webp", "/book/three" to "",
        ), "results-legacy" to listOf("/book/four" to "/covers/four.webp"), "landing" to emptyList())
        for ((id, results) in expected) {
            val page = AnalyzeRule().setContent(doc.select("#$id").outerHtml())
            val extracted = page.getElements(list).map { row ->
                val item = AnalyzeRule().setContent(row)
                item.getString("@css:a@href").orEmpty() to item.getString("@css:img@data-src||img@src").orEmpty()
            }
            assertEquals(results, extracted, id)
        }
    }

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
