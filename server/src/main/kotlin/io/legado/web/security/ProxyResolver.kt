package io.legado.web.security

import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import java.net.URI

/**
 * 书源请求使用的代理：管理员在系统设置里维护代理列表，每条代理只用于它列出的网站。
 * 用户不能选择代理，也不能把管理员的代理用到其他网站上。
 *
 * 优先级：规则里写的 proxy（用户自己的代理）> 书源设为不使用代理 > 按请求的网站匹配管理员的代理。
 * proxyMode：空 = 按网站匹配；"direct" = 不使用代理。
 *
 * 放在 io.legado.web 包下：书源脚本的 ClassShutter 屏蔽了这个包，脚本无法读取代理账号。
 */
object ProxyResolver {

    const val MODE_DIRECT = "direct"

    /** 一条代理：url 为标准格式 http|socks4|socks5://[user:pass@]host:port；sites 为使用它的网站（主机名或 *.后缀，逗号或换行分隔） */
    data class NamedProxy(val name: String = "", val url: String = "", val sites: String = "")

    data class Spec(
        val type: String,
        val host: String,
        val port: Int,
        val user: String,
        val pass: String,
        /** 管理员代理只能用于的网站；null = 不限制（规则里用户自己写的代理） */
        val sites: List<String>? = null,
    ) {
        /** 阅读原有的规则格式：socks5://host:port@user@password（见 HttpHelper.getProxyClient） */
        fun toLegado(): String = "$type://$host:$port" + if (user.isNotEmpty() && pass.isNotEmpty()) "@$user@$pass" else ""

        /** Chrome 的 proxyServer 参数（账号密码另外通过 Fetch.authRequired 提供） */
        fun toChrome(): String = "$type://$host:$port"

        /** 浏览器里经过这个代理的请求是否允许（管理员代理只允许列出的网站） */
        fun allows(url: String): Boolean = allows(sites, url)
    }

    /** sites 为 null 表示不限制；非 http(s) 地址（data:、blob: 等）不经过代理，不限制 */
    fun allows(sites: List<String>?, url: String): Boolean {
        if (sites == null) return true
        val h = hostOf(url) ?: return true
        return matches(sites, h)
    }

    /** 浏览器里整个页面共用一个代理，无法让其他网站的请求直连，只能拦掉；网站验证用到的 Cloudflare 验证码另外放行 */
    private val browserExtraSites = listOf("challenges.cloudflare.com")

    fun allowsInBrowser(sites: List<String>?, url: String): Boolean =
        sites == null || allows(sites, url) || allows(browserExtraSites, url)

    private var listSupplier: () -> String = { "" }

    /** 由 Application 启动时调用 */
    fun configure(list: () -> String) {
        listSupplier = list
    }

    /** 解析标准代理地址；格式不对返回 null */
    fun parse(url: String): Spec? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val type = u.scheme?.lowercase()?.takeIf { it in setOf("http", "socks4", "socks5") } ?: return null
        val host = u.host?.takeIf { it.isNotBlank() } ?: return null
        if (u.port !in 1..65535) return null
        val info = u.rawUserInfo.orEmpty()
        val user = java.net.URLDecoder.decode(info.substringBefore(':', info), Charsets.UTF_8)
        val pass = if (info.contains(':')) java.net.URLDecoder.decode(info.substringAfter(':'), Charsets.UTF_8) else ""
        // 阅读的规则格式用 @ 分隔账号密码，账号密码里不能再有 @
        if (user.contains('@') || pass.contains('@')) return null
        return Spec(type, host, u.port, user, pass)
    }

    /** 解析阅读规则格式 type://host:port@user@password（与 HttpHelper.getProxyClient 相同的正则） */
    fun fromLegado(proxy: String): Spec? {
        val g = Regex("(http|socks4|socks5)://(.*):(\\d{2,5})(@.*@.*)?").find(proxy)?.groupValues ?: return null
        val auth = g[4].split("@")
        return Spec(g[1], g[2], g[3].toInt(), auth.getOrElse(1) { "" }, auth.getOrElse(2) { "" })
    }

    /** 规则里写的 proxy 优先，否则按书源设置和网站匹配 */
    fun resolve(source: BaseSource?, url: String?, ruleProxy: String?): Spec? =
        if (!ruleProxy.isNullOrBlank()) fromLegado(ruleProxy) else resolve(source, url)

    @Volatile
    private var parsedFrom: String? = null

    @Volatile
    private var parsed: List<NamedProxy> = emptyList()

    /** 代理列表（JSON 数组 [{name,url,sites}]），解析失败按空列表处理 */
    fun list(): List<NamedProxy> {
        val text = listSupplier()
        if (text == parsedFrom) return parsed
        val list = parseList(text) ?: emptyList()
        parsed = list
        parsedFrom = text
        return list
    }

    /** 校验并解析管理员保存的代理列表；格式错误返回 null */
    fun parseList(text: String): List<NamedProxy>? {
        if (text.isBlank()) return emptyList()
        val arr = runCatching { GSON.fromJson(text, Array<NamedProxy>::class.java) }.getOrNull() ?: return null
        return arr.map { NamedProxy(it.name.orEmpty().trim(), it.url.orEmpty().trim(), it.sites.orEmpty().trim()) }
    }

    /** 网站列表：主机名或 *.后缀，逗号、空白或换行分隔 */
    fun siteList(text: String): List<String> =
        text.split(',', '\n', '\r', ' ', '\t').map { it.trim().trimEnd('.').lowercase() }.filter { it.isNotEmpty() }

    /** 主机名与网站列表匹配：example.com 只匹配自身，*.example.com 匹配 example.com 及其子域名 */
    fun matches(sites: List<String>, host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]").trimEnd('.').lowercase()
        return sites.any { e ->
            if (e.startsWith("*.")) h == e.substring(2) || h.endsWith(e.substring(1)) else h == e
        }
    }

    private fun hostOf(url: String): String? =
        runCatching { URI(url.substringBefore(",{").trim().replace(" ", "%20")).host }.getOrNull()

    /** 书源请求这个网址时使用的管理员代理；不使用代理时返回 null */
    fun resolve(source: BaseSource?, url: String?): Spec? {
        if ((source as? BookSource)?.proxyMode?.trim() == MODE_DIRECT) return null
        val host = url?.let { hostOf(it) } ?: return null
        for (p in list()) {
            val sites = siteList(p.sites)
            if (matches(sites, host)) return parse(p.url)?.copy(sites = sites)
        }
        return null
    }
}
