package io.legado.web

import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.TocEmptyException
import io.legado.web.security.NetworkGuard
import io.legado.web.service.SourceCheckErrors
import io.legado.web.service.SourceCheckHttpException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitCancellation
import org.htmlunit.corejs.javascript.EvaluatorException
import org.htmlunit.corejs.javascript.WrappedException

class SourceCheckErrorsTest {
    @Test
    fun classifiesRealFailuresIncludingWrappedCauses() {
        val cases = listOf(
            UnknownHostException("host") to "dns",
            IOException("Name or service not known") to "dns",
            IOException("Temporary failure in name resolution") to "dns",
            IOException("No address associated with hostname") to "dns",
            SocketTimeoutException("Read timed out") to "timeout",
            IOException("Connect timed out") to "timeout",
            IOException("timeout") to "timeout",
            SSLHandshakeException("handshake") to "tls",
            SSLException("internal_error") to "tls",
            IOException("unrecognized_name") to "tls",
            IOException("Unsupported or unrecognized SSL message") to "tls",
            IOException("certificate verify failed") to "tls",
            ConnectException("Connection refused") to "connection",
            IOException("stream was reset") to "connection",
            IOException("unexpected end of stream") to "connection",
            IOException("Connection reset") to "connection",
            EOFException() to "connection",
            SourceCheckHttpException(500, false) to "http",
            IOException("HTTP 404") to "http",
            EvaluatorException("bad script") to "rule",
            IOException("TypeError: Cannot read property \"1\" from null") to "rule",
            IOException("json string can not be null or empty") to "rule",
            IOException("InvalidPathException: JSONPath") to "rule",
            IOException("XPath parse error") to "rule",
            IOException("CSS parse error") to "rule",
            IOException("body is null") to "rule",
            NetworkGuard.BlockedException("127.0.0.1") to "blocked",
            SourceCheckHttpException(403, true) to "blocked",
            TocEmptyException("目录列表为空") to "noResult",
            ContentEmptyException("内容为空") to "noResult",
            IOException("uncertain failure") to "other",
            IOException("timeout in wrapper", UnknownHostException("host")) to "dns",
            EvaluatorException("Wrapped java.net.UnknownHostException: books.invalid (#1)") to "dns",
            EvaluatorException("Wrapped javax.net.ssl.SSLHandshakeException: handshake (#1)") to "tls",
            WrappedException(ConnectException("Connection refused")) to "connection",
            EvaluatorException("TypeError: timeout is not a function") to "rule",
        )
        for ((error, expected) in cases) {
            assertEquals(expected, SourceCheckErrors.classify(error), error.toString())
            assertEquals(expected, SourceCheckErrors.classify(Exception("stage failed", error)), error.toString())
        }
    }

    @Test
    fun overallTimeoutRemainsUncertain() = runBlocking {
        val error = try {
            withTimeout(1) { awaitCancellation() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            e
        }
        assertEquals("other", SourceCheckErrors.classify(error))
    }
}
