package io.legado.web

import io.legado.app.help.http.newCallStrResponse
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EncodingEvidenceTest {
    @Test
    fun recordsConflictingDeclarationsWithoutChangingHttpDecoding() = runBlocking {
        val html = "<head><meta charset='GBK'></head><body>中文</body>"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = html.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val response = OkHttpClient().newCallStrResponse { url("http://127.0.0.1:${server.address.port}/") }
            assertEquals(html, response.body)
            val evidence = requireNotNull(response.encoding)
            assertEquals("UTF-8", evidence.selectedCharset)
            assertEquals("GBK", evidence.metaCharset)
            assertEquals("http", evidence.reason)
            assertTrue(evidence.declarationConflict)
        } finally { server.stop(0) }
    }

    @Test
    fun recordsMalformedBytesAndReplacementCharacters() {
        val bytes = byteArrayOf(0xc3.toByte(), 0x28)
        val evidence = io.legado.app.help.http.EncodingEvidence.inspect(bytes, String(bytes, Charsets.UTF_8), "UTF-8", null, "UTF-8")
        assertTrue(evidence.malformedBytes)
        assertEquals(1, evidence.replacementCharacters)
    }
}
