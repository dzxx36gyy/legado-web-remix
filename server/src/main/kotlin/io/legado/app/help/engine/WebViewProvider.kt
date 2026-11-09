package io.legado.app.help.engine

/**
 * 代替 Android BackstageWebView 的无头浏览器接口
 */
interface WebViewProvider {

    data class Request(
        val url: String?,
        val html: String?,
        val javaScript: String?,
        val headerMap: Map<String, String>?,
        val sourceRegex: String? = null,
        val overrideUrlRegex: String? = null,
        val delayTime: Long = 0,
        val cookie: String? = null,
        /** 浏览器使用的代理（type://host:port），与书源的 HTTP 请求走同一出口 */
        val proxyServer: String? = null,
        val proxyUser: String? = null,
        val proxyPass: String? = null,
        /** 管理员代理只能用于的网站，其他网站的请求被拦掉；null = 不限制 */
        val proxySites: List<String>? = null,
        /** 网站验证模式：等待质询页通过（页面不再是质询页）后返回 Cookie 与 User-Agent */
        val challenge: Boolean = false,
    )

    data class Result(
        /** 最终地址 */
        val url: String,
        /** js 执行结果 / 页面源码 / 匹配到的资源地址 */
        val body: String,
    )

    suspend fun load(request: Request): Result
}
