package io.legado.web.webview

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.legado.app.data.entities.BookSource
import io.legado.app.help.engine.EngineContext
import io.legado.app.help.engine.WebViewProvider
import io.legado.web.config.DbSettings
import io.legado.web.db.DatabaseFactory
import io.legado.web.service.BrowserBridge
import io.legado.web.service.SettingService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class BrowserLifecycleTest {
    @Test
    fun completionWaitsForDisposalAndQueuedCancellationDoesNotOpenAnotherPage() = runBlocking {
        val disposing = CompletableDeferred<Unit>()
        val allowDispose = CompletableDeferred<Unit>()
        val opened = AtomicInteger()
        val server = embeddedServer(Netty, port = 0) {
            install(WebSockets)
            routing {
                get("/json/version") {
                    val port = call.request.local.localPort
                    call.respondText("""{"webSocketDebuggerUrl":"ws://localhost:$port/devtools/browser/test"}""")
                }
                webSocket("/devtools/browser/test") {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val request = JsonParser.parseString(frame.readText()).asJsonObject
                        val method = request.get("method").asString
                        if (method == "Target.disposeBrowserContext" && request.getAsJsonObject("params").get("browserContextId").asString == "ctx-1") { disposing.complete(Unit); allowDispose.await() }
                        val result = JsonObject().apply {
                            when (method) {
                                "Target.createBrowserContext" -> addProperty("browserContextId", "ctx-${opened.incrementAndGet()}")
                                "Target.createTarget" -> addProperty("targetId", "target")
                                "Target.attachToTarget" -> addProperty("sessionId", "session")
                                "Runtime.evaluate" -> add("result", JsonObject().apply { addProperty("value", "false") })
                                "Network.getCookies" -> add("cookies", com.google.gson.JsonArray())
                            }
                        }
                        val response = JsonObject().apply { add("id", request.get("id")); add("result", result) }
                        send(Frame.Text(response.toString()))
                        if (method == "Page.navigate") send(Frame.Text("""{"sessionId":"session","method":"Page.loadEventFired","params":{}}"""))
                    }
                }
            }
        }.start(false)
        val dir = Files.createTempDirectory("browser-lifecycle-").toFile()
        DatabaseFactory.init(DbSettings("sqlite", "jdbc:sqlite:$dir/test.db", "", "", 1))
        val settings = SettingService()
        val port = server.engine.resolvedConnectors().single().port
        settings.update(mapOf(SettingService.BROWSER_CDP to "http://localhost:$port"))
        settings.update(mapOf(SettingService.CLOAK_BROWSER_CDP to "http://localhost:$port", SettingService.CLOAK_BROWSER_SITES to "example.com"))
        val router = BrowserRouter(settings, BrowserContexts(dir.resolve("contexts")))
        val bridge = BrowserBridge().apply { browserRouter = router; remoteEnabled = { true } }
        val events = bridge.register(1, null)
        val source = BookSource().apply { bookSourceUrl = "https://example.com"; bookSourceName = "test" }
        fun launchRequest() = async(Dispatchers.IO) {
            EngineContext.with(EngineContext(1)) { bridge.getVerificationResult(source, "https://example.com", "test", true, null) }
        }
        try {
            withTimeout(15_000) {
                val first = launchRequest()
                val firstToken = JsonParser.parseString(events.receive()).asJsonObject.get("token").asString
                bridge.awaitRemote(1, firstToken) {}
                val second = launchRequest()
                val secondToken = JsonParser.parseString(events.receive()).asJsonObject.get("token").asString
                bridge.submit(1, secondToken, BrowserBridge.BrowserResult(cancel = true))
                second.await()
                val completing = async { bridge.submit(1, firstToken, BrowserBridge.BrowserResult()) }
                disposing.await()
                assertFalse(completing.isCompleted)
                assertEquals(1, opened.get())
                val cloak = async {
                    router.load(WebViewProvider.Request("https://example.com", null, null, null))
                }
                val chromium = async { router.load(WebViewProvider.Request("https://other.example", null, null, null)) }
                assertEquals("false", chromium.await().body)
                assertFalse(cloak.isCompleted)
                assertEquals(2, opened.get())
                allowDispose.complete(Unit)
                completing.await()
                first.await()
                assertEquals("false", cloak.await().body)
                assertFailsWith<io.legado.web.auth.NotFoundException> { bridge.remoteSession(1, firstToken) }
                assertEquals(3, opened.get())
            }
        } finally {
            allowDispose.complete(Unit)
            bridge.unregister(1, events)
            router.close()
            server.stop(0, 1000)
        }
    }
}
