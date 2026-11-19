package io.legado.web.service.ai

import io.legado.app.constant.AppPattern
import org.htmlunit.corejs.javascript.EvaluatorException
import org.htmlunit.corejs.javascript.Parser
import org.htmlunit.corejs.javascript.Token
import org.htmlunit.corejs.javascript.ast.*

/** Only a closed expression grammar is auto-approved; model assurances never grant script capabilities. */
object AiRuleRisk {
    private val textMethods = setOf("trim", "replace", "replaceAll", "substring", "slice", "toLowerCase", "toUpperCase", "concat")
    private val mixedExecution = Regex("(?i)\\{\\{|@webjs:|javascript:|##|@put:|[,，]\\s*\\{")

    fun reasons(diff: List<RepairDiff>, simpleScripts: Boolean = false): List<String> = diff.filter { it.javascript }.mapNotNull { item ->
        val value = item.new.orEmpty().trim()
        val field = item.field.substringAfterLast('.')
        if (field in setOf("webJs", "callBackJs", "preUpdateJs", "imageDecode", "payAction", "contentBatch") || mixedExecution.containsMatchIn(value)) {
            return@mapNotNull "${item.field}: 无法确定脚本执行范围"
        }
        val matcher = AppPattern.JS_PATTERN.matcher(value)
        val script = if (field == "formatJs") value else {
            if (!matcher.find() || matcher.end() != value.length) return@mapNotNull "${item.field}: 无法确定脚本执行范围"
            (matcher.group(1) ?: matcher.group(2)).also {
                if (matcher.find()) return@mapNotNull "${item.field}: 无法确定脚本执行范围"
            }
        }
        val root = try { Parser().parse(script, "ai-rule", 1) } catch (e: EvaluatorException) {
            return@mapNotNull "${item.field}: 无法解析脚本"
        }
        val statements = root.statements
        if (statements.size == 1 && statements.single() is ExpressionStatement &&
            pure((statements.single() as ExpressionStatement).expression, simpleScripts)) null
        else "${item.field}: 脚本包含外部能力或无法证明为纯文本处理"
    }

    private fun pure(node: AstNode, simpleScripts: Boolean): Boolean = when (node) {
        is StringLiteral, is NumberLiteral, is RegExpLiteral -> true
        is Name -> node.identifier == "result"
        is ParenthesizedExpression -> pure(node.expression, simpleScripts)
        is ConditionalExpression -> simpleScripts && condition(node.testExpression) && text(node.trueExpression, simpleScripts) && text(node.falseExpression, simpleScripts)
        is InfixExpression -> simpleScripts && node.operator == Token.ADD && text(node.left, simpleScripts) && text(node.right, simpleScripts)
        is NewExpression -> false
        is FunctionCall -> when (val target = node.target) {
            is Name -> target.identifier == "String" && node.arguments.size == 1 && pure(node.arguments.single(), simpleScripts)
            is PropertyGet -> target.property.identifier in textMethods && text(target.target, simpleScripts) &&
                node.arguments.all { it is StringLiteral || it is NumberLiteral || it is RegExpLiteral || simpleScripts && text(it, true) }
            else -> false
        }
        else -> false
    }

    private fun condition(node: AstNode): Boolean = when (node) {
        is ParenthesizedExpression -> condition(node.expression)
        is InfixExpression -> node.operator in setOf(Token.SHEQ, Token.SHNE) && text(node.left, false) && text(node.right, false)
        else -> text(node, false)
    }

    private fun text(node: AstNode, simpleScripts: Boolean): Boolean = when (node) {
        is StringLiteral -> true
        is ParenthesizedExpression -> text(node.expression, simpleScripts)
        is NewExpression -> false
        is FunctionCall, is ConditionalExpression, is InfixExpression -> pure(node, simpleScripts)
        else -> false
    }
}
