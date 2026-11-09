package io.legado.app.help.http

import io.legado.app.exception.UnsupportedOnServerException
import io.legado.app.help.engine.Engine
import io.legado.app.help.engine.WebViewProvider
import io.legado.app.model.Debug

/**
 * 替代 Android 后台 WebView。
 * 配置了无头浏览器（[Engine.webViewProvider]）时交给浏览器执行；
 * 否则退化为普通 HTTP 请求（无法执行页面 js）。
 */
class BackstageWebView(
    private val url: String? = null,
    private val html: String? = null,
    private val encode: String? = null,
    private val tag: String? = null,
    private val headerMap: Map<String, String>? = null,
    private val sourceRegex: String? = null,
    private val overrideUrlRegex: String? = null,
    private val javaScript: String? = null,
    /** 阅读的 WebView 缓存优先选项，网页版无意义，保留参数以兼容 */
    private val cacheFirst: Boolean = false,
    private val delayTime: Long = 0,
    private val timeout: Long? = null,
    private val result: String? = null,
    private val isRule: Boolean = false,
    /** 服务端新增：用于选择代理（与书源的 HTTP 请求走同一出口） */
    private val source: io.legado.app.data.entities.BaseSource? = null,
    private val ruleProxy: String? = null,
) {

    suspend fun getStrResponse(): StrResponse {
        val provider = Engine.webViewProvider
        val proxy = io.legado.web.security.ProxyResolver.resolve(source, url, ruleProxy)
        if (provider != null) {
            proxy?.let { io.legado.web.security.NetworkGuard.checkHost(it.host) }
            val cookie = url?.let { CookieStore.getCookie(it) }
            val result = provider.load(
                WebViewProvider.Request(
                    url = url,
                    html = html,
                    javaScript = javaScript,
                    headerMap = headerMap,
                    sourceRegex = sourceRegex,
                    overrideUrlRegex = overrideUrlRegex,
                    delayTime = delayTime,
                    cookie = cookie,
                    proxyServer = proxy?.toChrome(),
                    proxyUser = proxy?.user,
                    proxyPass = proxy?.pass,
                    proxySites = proxy?.sites,
                )
            )
            return StrResponse(result.url, result.body)
        }
        if (!sourceRegex.isNullOrBlank() || !overrideUrlRegex.isNullOrBlank()) {
            throw UnsupportedOnServerException("webView 资源嗅探（未配置无头浏览器）")
        }
        Debug.log(tag, "⇒未配置无头浏览器，webView 退化为普通请求，页面 js 不会执行")
        if (!html.isNullOrEmpty()) {
            return StrResponse(url ?: "http://localhost/", html)
        }
        val targetUrl = url ?: throw UnsupportedOnServerException("webView 无 url 与 html")
        return getProxyClient(proxy?.toLegado()).newCallStrResponse {
            headerMap?.let { addHeaders(it) }
            url(targetUrl)
        }
    }
}
