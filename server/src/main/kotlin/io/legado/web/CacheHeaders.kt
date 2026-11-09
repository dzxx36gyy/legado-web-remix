package io.legado.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.request.path

/**
 * 给每个响应写明 Cache-Control，部署在 CDN 后面时由它决定哪些内容可以缓存：
 * - 页面（text/html，包括前端路由回退的 index.html）、Service Worker、清单：每次回源确认（有 ETag / Last-Modified，
 *   未变化时返回 304），发布新版本后立刻生效
 * - /assets/ 和 workbox-*.js：文件名带内容哈希，公开缓存 1 年
 * - pdfjs、图标等其他前端静态文件：公开缓存 1 天（只在升级时变化）
 * - /api、/opds、/openapi：接口自己设置了缓存头就用接口的，否则 private, no-store，CDN 和浏览器都不保存
 *
 * 要在 ConditionalHeaders 之前安装，304 响应也带上同样的缓存头。
 */
val CacheHeaders = createApplicationPlugin("CacheHeaders") {
    on(ResponseBodyReadyForSend) { call, content ->
        val headers = call.response.headers
        if (headers.contains(HttpHeaders.CacheControl) || content.headers.contains(HttpHeaders.CacheControl)) return@on
        val status = content.status ?: call.response.status() ?: HttpStatusCode.OK
        headers.append(HttpHeaders.CacheControl, cachePolicy(call.request.path(), content.contentType, status))
    }
}

private val hashedFile = Regex("^/(assets/.+|workbox-[0-9a-f]+\\.js)$")

internal fun cachePolicy(path: String, contentType: ContentType?, status: HttpStatusCode): String = when {
    path.startsWith("/api") || path.startsWith("/opds") || path.startsWith("/openapi") -> "private, no-store"
    !status.isSuccess() && status != HttpStatusCode.NotModified -> "no-store"
    contentType?.match(ContentType.Text.Html) == true || path == "/" -> "no-cache"
    path == "/sw.js" || path == "/registerSW.js" || path.endsWith(".webmanifest") -> "no-cache"
    hashedFile.matches(path) -> "public, max-age=31536000, immutable"
    else -> "public, max-age=86400"
}
