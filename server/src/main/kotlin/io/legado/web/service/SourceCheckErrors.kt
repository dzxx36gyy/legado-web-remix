package io.legado.web.service

import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.TocEmptyException
import io.legado.web.security.NetworkGuard
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.TimeoutCancellationException
import org.htmlunit.corejs.javascript.RhinoException

/** 校验专用的 HTTP 错误，避免错误页被解析成“没有结果”。 */
internal class SourceCheckHttpException(val status: Int, val blocked: Boolean) :
    Exception("HTTP $status${if (blocked) " (website challenge)" else ""}")

/** 稳定机器码；先使用完整原因链中的类型，再匹配引擎保留的错误文字。 */
internal object SourceCheckErrors {
    fun classify(error: Throwable): String {
        val causes = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && causes.none { it === current }) {
            causes += current
            current = current.cause
        }
        if (causes.any { it is TimeoutCancellationException }) return "other"
        for (cause in causes.asReversed()) {
            when (cause) {
                is NetworkGuard.BlockedException -> return "blocked"
                is SourceCheckHttpException -> return if (cause.blocked) "blocked" else "http"
                is UnknownHostException -> return "dns"
                is SocketTimeoutException -> return "timeout"
                is SSLException -> return "tls"
                is ConnectException, is SocketException, is EOFException -> return "connection"
                is TocEmptyException, is ContentEmptyException -> return "noResult"
                is RhinoException -> return "rule"
            }
        }
        val message = causes.joinToString("\n") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }.lowercase()
        return when {
            listOf("name or service not known", "temporary failure in name resolution", "no address associated with hostname", "unknownhostexception").any { it in message } -> "dns"
            listOf("timed out", "timeout").any { it in message } -> "timeout"
            listOf("ssl", "tls", "internal_error", "unrecognized_name", "certificate", "certpath").any { it in message } -> "tls"
            listOf("connection refused", "stream was reset", "unexpected end of stream", "connection reset", "eofexception").any { it in message } -> "connection"
            Regex("\\bhttp(?:/[\\d.]+)?(?: status(?: code)?)?[: =]+[45]\\d{2}\\b").containsMatchIn(message) -> "http"
            listOf("已阻止访问内网地址", "网站验证未在", "cloudflare challenge").any { it in message } -> "blocked"
            listOf("typeerror:", "referenceerror:", "syntaxerror:", "json string can not be null or empty", "jsonpath", "invalidpath", "xpath", "selectparse", "css parse", "body is null", "body为空", "body 为空", "书源没有搜索和发现地址").any { it in message } -> "rule"
            else -> "other"
        }
    }
}
