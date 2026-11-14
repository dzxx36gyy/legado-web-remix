package io.legado.web

import io.legado.web.service.ai.AiRuleRisk
import io.legado.web.service.ai.RepairDiff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiRuleRiskTest {
    @Test
    fun onlyClosedTextExpressionsAreAutomaticallyApproved() {
        val cases = listOf(
            "@js:result" to true,
            "@js:String(result).trim().replace(/广告/g, '')" to true,
            "<js>String(result).slice(1)</js>" to true,
            "@js:java.ajax('https://example.com')" to false,
            "@js:java.get('secret')" to false,
            "@js:String(result)['constructor']('return java')()" to false,
            "@js:eval('result')" to false,
            "@js:result.trim()" to false,
            "@js:String(result).replace('x', result)" to false,
            "@js:new String(result)" to false,
            "@js:result; java.ajax('https://example.com')" to false,
            "{{java.ajax('https://example.com')}}" to false,
            "@webjs:document.body" to false,
            "@js:(" to false,
        )
        for ((rule, safe) in cases) {
            assertEquals(safe, AiRuleRisk.reasons(listOf(RepairDiff("ruleContent.content", null, rule, true))).isEmpty(), rule)
        }
        assertTrue(AiRuleRisk.reasons(listOf(RepairDiff("ruleSearch.bookList", ".old", ".new", false))).isEmpty())
        assertTrue(AiRuleRisk.reasons(listOf(RepairDiff("ruleToc.formatJs", null, "java.ajax('https://example.com'); //@js:result", true))).isNotEmpty())
    }
}
