package io.legado.web.webview

import io.legado.app.data.entities.BaseSource
import io.legado.app.help.CacheManager
import io.legado.app.help.engine.Engine
import io.legado.app.help.engine.EngineContext
import io.legado.app.help.engine.WebViewProvider
import io.legado.app.help.http.CookieStore
import io.legado.app.model.Debug
import io.legado.app.utils.NetworkUtils
import io.legado.web.security.NetworkGuard
import io.legado.web.security.ProxyResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import okhttp3.Response
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 自动通过网站验证（Cloudflare 等的 JS 质询页）。
 *
 * 书源的普通请求拿到质询页时，用浏览器（系统设置中的浏览器调试地址）打开同一网址等待质询通过，
 * 把得到的 Cookie 写回书源请求用的 Cookie、记下浏览器的 User-Agent，然后重试。
 * cf_clearance 与出口 IP、User-Agent 绑定，所以浏览器与书源请求使用同一个代理，之后的请求也改用浏览器的 User-Agent。
 *
 * 同一用户、同一网站、同一代理同时只解一次（其他请求等待结果）；失败后一分钟内不再尝试，避免反复打开浏览器。
 * 放在 io.legado.web 包下：书源脚本无法调用。
 */
object ChallengeSolver {

    private const val FAIL_COOLDOWN_MS = 60_000L

    /** 浏览器 User-Agent 的保存时间（秒），与 cf_clearance 的常见有效期相当 */
    private const val UA_TTL_SECONDS = 7 * 24 * 3600

    private val inflight = ConcurrentHashMap<String, Deferred<Boolean>>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    /** Application 启动时替换为应用作用域（关闭时统一取消）；默认值只给测试和未装配的调用使用 */
    @Volatile
    var scope: CoroutineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** 质询页特征（只在 403 / 429 / 503 响应里查找） */
    private val markers = listOf(
        "<title>Just a moment",
        "Checking your browser",
        "cf-browser-verification",
        "_cf_chl_opt",
        "/cdn-cgi/challenge-platform/",
        "jschl-answer",
    )

    /** 响应是否是网站验证的质询页 */
    fun isChallenge(res: Response, body: String?): Boolean {
        if (res.header("cf-mitigated")?.equals("challenge", true) == true) return true
        if (res.code != 403 && res.code != 429 && res.code != 503) return false
        val head = body?.take(32 * 1024) ?: return false
        return markers.any { head.contains(it, true) }
    }

    private fun uaKey(url: String) = "challengeUA_" + NetworkUtils.getSubDomain(url)

    /** 通过验证时浏览器的 User-Agent（当前用户），之后对该网站的请求使用它 */
    fun userAgentFor(url: String): String? =
        runCatching { CacheManager.get(uaKey(url)) }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 记下通过验证时浏览器的 User-Agent（当前用户） */
    fun saveUserAgent(url: String, ua: String) {
        if (ua.isNotBlank()) CacheManager.put(uaKey(url), ua, UA_TTL_SECONDS)
    }

    /** 保存浏览器得到的验证结果（Cookie 与 User-Agent）到当前用户 */
    fun saveResult(url: String, cookies: String?, ua: String?) {
        if (!cookies.isNullOrBlank()) CookieStore.replaceCookie(url, cookies)
        if (!ua.isNullOrBlank()) saveUserAgent(url, ua)
    }

    /**
     * 用浏览器打开网址等待验证通过，成功时 Cookie 与 User-Agent 已写回。
     * @return false：没有配置浏览器、最近失败过或验证未通过
     */
    suspend fun solve(source: BaseSource?, url: String, ruleProxy: String?): Boolean {
        val provider = Engine.webViewProvider ?: return false
        val ctx = EngineContext.current
        val host = runCatching { URI(url.substringBefore(",{").trim().replace(" ", "%20")).host }.getOrNull() ?: return false
        val proxy = ProxyResolver.resolve(source, url, ruleProxy)
        val key = "${ctx?.userId ?: 0}|$host|${proxy?.toChrome().orEmpty()}"
        failedAt[key]?.let { if (System.currentTimeMillis() - it < FAIL_COOLDOWN_MS) return false }
        val job = inflight.computeIfAbsent(key) {
            scope.async(ctx ?: EmptyCoroutineContext, start = CoroutineStart.LAZY) {
                run(provider, source, url, proxy).also { ok -> if (!ok) failedAt[key] = System.currentTimeMillis() else failedAt.remove(key) }
            }
        }
        job.invokeOnCompletion { inflight.remove(key, job) }
        job.start()
        // 等待浏览器通过验证（最多约 45 秒）期间不计入书源请求的超时，否则搜索等操作先超时，失败时也来不及交给用户手动通过
        ctx?.waitingForUser?.incrementAndGet()
        val started = System.currentTimeMillis()
        try {
            return job.await()
        } finally {
            ctx?.userWaitMs?.addAndGet(System.currentTimeMillis() - started)
            ctx?.waitingForUser?.decrementAndGet()
        }
    }

    private suspend fun run(
        provider: WebViewProvider,
        source: BaseSource?,
        url: String,
        proxy: ProxyResolver.Spec?,
    ): Boolean {
        val tag = source?.getKey()
        Debug.log(tag, "⇒遇到网站验证（Cloudflare 等），用浏览器自动通过")
        val result = try {
            proxy?.let { NetworkGuard.checkHost(it.host) }
            val pageUrl = url.substringBefore(",{")
            provider.load(
                WebViewProvider.Request(
                    url = pageUrl,
                    html = null,
                    javaScript = null,
                    headerMap = null,
                    cookie = CookieStore.getCookie(pageUrl),
                    proxyServer = proxy?.toChrome(),
                    proxyUser = proxy?.user,
                    proxyPass = proxy?.pass,
                    proxySites = proxy?.sites,
                    challenge = true,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Debug.log(tag, "⇒网站验证未能自动通过: ${e.message}")
            return false
        }
        saveResult(url, result.cookies, result.userAgent)
        Debug.log(tag, "⇒网站验证已通过，重新请求")
        return true
    }
}
