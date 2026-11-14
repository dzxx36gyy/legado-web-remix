package io.legado.web.service.ai

import org.htmlunit.corejs.javascript.EvaluatorException
import org.htmlunit.corejs.javascript.Parser
import org.htmlunit.corejs.javascript.ast.*

/** Only a closed expression grammar is auto-approved; model assurances never grant script capabilities. */
object AiRuleRisk {
    private val textMethods = setOf("trim", "replace", "replaceAll", "substring", "slice", "toLowerCase", "toUpperCase", "concat")

    fun reasons(diff: List<RepairDiff>): List<String> = diff.filter { it.javascript }.mapNotNull { item ->
        val value = item.new.orEmpty().trim()
        val script = when {
            value.startsWith("@js:", true) -> value.substring(4)
            value.startsWith("<js>", true) && value.endsWith("</js>", true) -> value.substring(4, value.length - 5)
            item.field.substringAfterLast('.').contains("js", true) -> value
            value.contains("@js:") && !Regex("(?i)\\{\\{|<js>|@webjs:|javascript:").containsMatchIn(value.substringBefore("@js:")) -> value.substringAfter("@js:")
            else -> return@mapNotNull "${item.field}: 无法确定脚本执行范围"
        }
        val root = try { Parser().parse(script, "ai-rule", 1) } catch (e: EvaluatorException) {
            return@mapNotNull "${item.field}: 无法解析脚本"
        }
        val statements = root.statements
        if (statements.size == 1 && statements.single() is ExpressionStatement &&
            pure((statements.single() as ExpressionStatement).expression)) null
        else "${item.field}: 脚本包含外部能力或无法证明为纯文本处理"
    }

    private fun pure(node: AstNode): Boolean = when (node) {
        is StringLiteral, is NumberLiteral, is RegExpLiteral -> true
        is Name -> node.identifier == "result"
        is ParenthesizedExpression -> pure(node.expression)
        is NewExpression -> false
        is FunctionCall -> when (val target = node.target) {
            is Name -> target.identifier == "String" && node.arguments.size == 1 && pure(node.arguments.single())
            is PropertyGet -> target.property.identifier in textMethods && text(target.target) && node.arguments.all { it is StringLiteral || it is NumberLiteral || it is RegExpLiteral }
            else -> false
        }
        else -> false
    }

    private fun text(node: AstNode): Boolean = when (node) {
        is StringLiteral -> true
        is ParenthesizedExpression -> text(node.expression)
        is FunctionCall -> pure(node)
        else -> false
    }
}
