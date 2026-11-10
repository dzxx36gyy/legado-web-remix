package io.legado.web.service

import io.legado.app.data.entities.BookSource
import io.legado.app.help.engine.Engine
import io.legado.app.help.engine.EngineContext
import io.legado.app.help.http.CookieStore
import io.legado.app.utils.NetworkUtils
import io.legado.web.auth.BadRequestException
import io.legado.web.db.Cookies
import io.legado.web.db.dbQuery
import io.legado.web.security.NetworkGuard
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.concurrent.ConcurrentHashMap

/**
 * 网站账号：用户在任意网站登录后保存的 Cookie（按网站，即二级域名，与书源请求共用）。
 * - 列出 / 清除已保存的 Cookie（书源用的和远程浏览器自己的一起）
 * - 在远程浏览器中打开任意网址手动登录，关闭时 Cookie 自动保存
 * - 手动填写 Cookie
 * - 书源请求发现登录已失效（跳转到登录页、401）时提示用户
 */
class SiteAccountService(private val bridge: BrowserBridge, private val notifier: (Long, String?, String) -> Unit) {
    private val expiredNoticed = ConcurrentHashMap<String, Long>()

    data class SiteAccount(
        /** 网站（二级域名） */
        val domain: String,
        /** 书源请求用的 Cookie 名称 */
        val names: List<String>,
        /** 远程浏览器自己保存的 Cookie 名称 */
        val browserNames: List<String>,
    )

    data class CookieRequest(val url: String = "", val cookie: String = "")
    data class LoginRequest(val url: String = "", val client: String? = null)

    suspend fun list(userId: Long): List<SiteAccount> {
        val rows = dbQuery {
            Cookies.selectAll().where { Cookies.userId eq userId }.map { it[Cookies.domain] to it[Cookies.cookie] }
        }
        val own = mutableMapOf<String, List<String>>()
        val browser = mutableMapOf<String, List<String>>()
        rows.forEach { (domain, cookie) ->
            val names = CookieStore.cookieToMap(cookie).keys.toList()
            if (names.isEmpty()) return@forEach
            if (domain.startsWith(BROWSER_PREFIX)) browser[domain.removePrefix(BROWSER_PREFIX)] = names else own[domain] = names
        }
        return (own.keys + browser.keys).sorted().map { SiteAccount(it, own[it].orEmpty(), browser[it].orEmpty()) }
    }

    /** 清除一个网站的登录状态（书源 Cookie、会话 Cookie 和远程浏览器的 Cookie） */
    fun clear(userId: Long, domain: String) {
        val d = domain.trim().lowercase()
        if (d.isEmpty() || d.length > 255 || d.contains('/')) throw BadRequestException("网站无效")
        EngineContext.with(EngineContext(userId)) { CookieStore.removeCookie("http://$d/") }
        Engine.store.deleteCookie(userId, d)
        Engine.store.deleteCookie(userId, BROWSER_PREFIX + d)
        expiredNoticed.keys.removeIf { it == "$userId|$d" }
    }

    /** 手动填写的 Cookie：合并保存到该网站 */
    fun setCookie(userId: Long, req: CookieRequest): String {
        val url = httpUrl(req.url)
        val cookie = normalizeCookieInput(req.cookie) ?: throw BadRequestException("Cookie 无效")
        bridge.saveCookie(userId, url, cookie)
        return NetworkUtils.getSubDomain(url)
    }

    /**
     * 在远程浏览器中打开网址让用户登录，不等待结果；网页关闭时（完成或取消）Cookie 自动保存。
     * 没有可用的远程浏览器时报错（前端改为提示手动填写 Cookie）
     */
    fun login(userId: Long, req: LoginRequest) {
        val url = httpUrl(req.url)
        if (!bridge.remoteAvailable()) throw BadRequestException("没有可用的远程浏览器，请手动填写 Cookie")
        val host = url.toHttpUrlOrNull()!!.host
        NetworkGuard.checkHost(host)
        // 以该网站为“书源”打开：没有书源设置，代理按全局设置
        val source = BookSource(bookSourceUrl = url.toHttpUrlOrNull()!!.resolve("/").toString(), bookSourceName = host)
        val ctx = EngineContext(userId).apply { clientId = req.client?.takeIf { it.length in 8..64 } }
        val opened = EngineContext.with(ctx) { bridge.openAsync(source, url, host) }
        if (!opened) throw BadRequestException("请先打开页面再登录")
    }

    private fun httpUrl(raw: String): String {
        val u = raw.trim().let { if (it.contains("://")) it else "https://$it" }
        val parsed = u.toHttpUrlOrNull() ?: throw BadRequestException("地址无效")
        return parsed.toString()
    }

    companion object {
        /** 与 [BrowserCookies] 相同的前缀：远程浏览器自己的 Cookie */
        private const val BROWSER_PREFIX = "webview|"
        /** 同一网站的登录失效提示间隔 */
        private const val NOTICE_INTERVAL_MS = 30 * 60_000L
        private val loginPath = Regex("""(^|/)(login|signin|sign_in|sign-in|passport|auth|account/login)([/.?]|$)""", RegexOption.IGNORE_CASE)
    }

        /**
         * 书源请求的响应：该网站保存过 Cookie，但请求被跳转到登录页或返回 401 时，提示用户登录已失效
         * （每个网站 [NOTICE_INTERVAL_MS] 内只提示一次）
         */
        fun checkResponse(requestUrl: String, res: Response) {
            val ctx = EngineContext.current ?: return
            if (ctx.userId == 0L || ctx.suppressUi) return
            val finalPath = res.request.url.encodedPath
            val redirectedToLogin = res.priorResponse != null && loginPath.containsMatchIn(finalPath) &&
                requestUrl.toHttpUrlOrNull()?.encodedPath?.let { loginPath.containsMatchIn(it) } != true
            if (!redirectedToLogin && res.code != 401) return
            val domain = NetworkUtils.getSubDomainOrNull(requestUrl) ?: return
            if (Engine.store.getCookie(ctx.userId, domain).isNullOrBlank()) return
            val key = "${ctx.userId}|$domain"
            val now = System.currentTimeMillis()
            val last = expiredNoticed[key]
            if (last != null && now - last < NOTICE_INTERVAL_MS) return
            expiredNoticed[key] = now
            notifier(ctx.userId, ctx.clientId, domain)
        }
}
