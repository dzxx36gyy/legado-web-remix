package io.legado.web.webview

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.legado.app.help.engine.WebViewProvider
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BrowserStatusTest {
    @Test
    fun onlyDeniedMainDocumentsSuggestSwitchingBrowser() = runBlocking {
        var status = 200
        var frameId = "target"
        var type = "Document"
        val server = embeddedServer(Netty, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/cdp") {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val request = JsonParser.parseString(frame.readText()).asJsonObject
                        val method = request.get("method").asString
                        val result = JsonObject().apply {
                            when (method) {
                                "Target.createBrowserContext" -> addProperty("browserContextId", "context")
                                "Target.createTarget" -> addProperty("targetId", "target")
                                "Target.attachToTarget" -> addProperty("sessionId", "session")
                                "Runtime.evaluate" -> add("result", JsonObject().apply { addProperty("value", "page") })
                            }
                        }
                        send(Frame.Text(JsonObject().apply { add("id", request.get("id")); add("result", result) }.toString()))
                        if (method == "Page.navigate") {
                            send(Frame.Text(JsonObject().apply {
                                addProperty("sessionId", "session")
                                addProperty("method", "Network.responseReceived")
                                add("params", JsonObject().apply {
                                    addProperty("frameId", frameId)
                                    addProperty("type", type)
                                    add("response", JsonObject().apply {
                                        addProperty("status", status)
                                        addProperty("url", "https://redirect.example/verify")
                                    })
                                })
                            }.toString()))
                            send(Frame.Text("""{"sessionId":"session","method":"Page.loadEventFired","params":{}}"""))
                        }
                    }
                }
            }
        }.start(false)
        val dir = Files.createTempDirectory("browser-status-").toFile()
        val endpoint = "ws://localhost:${server.engine.resolvedConnectors().single().port}/cdp"
        try {
            CdpWebViewProvider({ endpoint }, BrowserContexts(dir.resolve("contexts"))).use { provider ->
                for (code in listOf(401, 403, 418)) {
                    status = code
                    val error = assertFailsWith<BrowserChallengeException> {
                        provider.load(WebViewProvider.Request(url = "https://example.com", html = null, javaScript = null, headerMap = null))
                    }
                    assertEquals(code, error.status)
                    assertEquals("https://redirect.example/verify", error.url)
                    assertEquals("默认浏览器访问网站返回 HTTP $code", error.message)
                }
                for (code in listOf(200, 429, 500, 503)) {
                    status = code
                    assertEquals("page", provider.load(WebViewProvider.Request(url = "https://example.com", html = null, javaScript = null, headerMap = null)).body)
                }
                status = 403
                frameId = "child-frame"
                assertEquals("page", provider.load(WebViewProvider.Request(url = "https://example.com", html = null, javaScript = null, headerMap = null)).body)
                frameId = "target"
                type = "Image"
                assertEquals("page", provider.load(WebViewProvider.Request(url = "https://example.com", html = null, javaScript = null, headerMap = null)).body)
            }
            status = 403
            type = "Document"
            CdpWebViewProvider({ endpoint }, BrowserContexts(dir.resolve("cloak-contexts")), usingCloak = true).use { provider ->
                val error = assertFailsWith<BrowserChallengeException> {
                    provider.load(WebViewProvider.Request(url = "https://example.com", html = null, javaScript = null, headerMap = null))
                }
                assertEquals("已使用 Cloak 访问，但网站仍返回 HTTP 403", error.message)
            }
        } finally {
            server.stop(0, 1000)
            dir.deleteRecursively()
        }
    }
}
