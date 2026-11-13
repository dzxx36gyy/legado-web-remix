package io.legado.web.webview

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import io.legado.app.help.engine.Engine
import io.legado.app.help.engine.WebViewProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class ChallengeSolverTest {

    private fun res(code: Int, header: Pair<String, String>? = null): Response =
        Response.Builder()
            .request(Request.Builder().url("https://book.example.com/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .apply { header?.let { header(it.first, it.second) } }
            .build()

    private val cfPage = "<!DOCTYPE html><html><head><title>Just a moment...</title></head>" +
        "<body><script>window._cf_chl_opt={}</script></body></html>"

    @Test
    fun detectsChallengePages() {
        assertTrue(ChallengeSolver.isChallenge(res(503), cfPage))
        assertTrue(ChallengeSolver.isChallenge(res(403), "<form action=\"/cdn-cgi/challenge-platform/h/b\">"))
        assertTrue(ChallengeSolver.isChallenge(res(429), "checking your browser before accessing"))
        // cf-mitigated 头不看状态码和内容
        assertTrue(ChallengeSolver.isChallenge(res(200, "cf-mitigated" to "challenge"), null))
    }

    @Test
    fun ignoresOrdinaryResponses() {
        // 正常页面里出现特征文字不算
        assertFalse(ChallengeSolver.isChallenge(res(200), cfPage))
        assertFalse(ChallengeSolver.isChallenge(res(404), cfPage))
        // 普通的 403 / 503
        assertFalse(ChallengeSolver.isChallenge(res(403), "<h1>Forbidden</h1>"))
        assertFalse(ChallengeSolver.isChallenge(res(503), null))
        // 特征只在开头 32KB 内查找
        assertFalse(ChallengeSolver.isChallenge(res(503), "x".repeat(32 * 1024) + cfPage))
    }

    @Test
    fun providerCancellationIsNotConvertedToUnsolvedChallenge() = runBlocking {
        val previous = Engine.webViewProvider
        val previousScope = ChallengeSolver.scope
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        ChallengeSolver.scope = testScope
        Engine.webViewProvider = object : WebViewProvider {
            override suspend fun load(request: WebViewProvider.Request): WebViewProvider.Result {
                throw CancellationException("browser cancelled")
            }
        }
        try {
            val error = assertFailsWith<CancellationException> {
                ChallengeSolver.solve(null, "https://cancelled-challenge.example/", null)
            }
            kotlin.test.assertEquals("browser cancelled", error.message)
        } finally {
            Engine.webViewProvider = previous
            ChallengeSolver.scope = previousScope
            testScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
