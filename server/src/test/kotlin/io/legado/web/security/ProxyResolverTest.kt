package io.legado.web.security

import io.legado.app.data.entities.BookSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyResolverTest {

    private var list = ""

    @BeforeTest
    fun setUp() {
        list = """[{"name":"a","url":"socks5://u:p%3Aw@203.0.113.4:1080","sites":"book.example.com\n*.example.org"},""" +
            """{"name":"b","url":"http://203.0.113.8:3128","sites":"other.net, example.org"}]"""
        ProxyResolver.configure { list }
    }

    @AfterTest
    fun reset() {
        ProxyResolver.configure { "" }
    }

    private fun source(mode: String?) = BookSource(bookSourceUrl = "https://book.example.com").apply { proxyMode = mode }

    @Test
    fun parsesStandardUrl() {
        val s = ProxyResolver.parse("socks5://user:p%3Ass@host.example:1080")!!
        assertEquals(ProxyResolver.Spec("socks5", "host.example", 1080, "user", "p:ss"), s)
        assertEquals("socks5://host.example:1080@user@p:ss", s.toLegado())
        assertEquals("socks5://host.example:1080", s.toChrome())
        assertEquals("http://h:8080", ProxyResolver.parse("http://h:8080")!!.toLegado())
        assertNull(ProxyResolver.parse("ftp://h:21"))
        assertNull(ProxyResolver.parse("http://h"))
        assertNull(ProxyResolver.parse("not a url"))
        // 阅读的规则格式用 @ 分隔，账号密码里不能有 @
        assertNull(ProxyResolver.parse("socks5://u%40x:p@h:1080"))
    }

    @Test
    fun legadoFormatRoundTrip() {
        val s = ProxyResolver.fromLegado("socks5://198.51.100.1:1080@user@pass")!!
        assertEquals(ProxyResolver.Spec("socks5", "198.51.100.1", 1080, "user", "pass"), s)
        assertEquals("socks5://198.51.100.1:1080@user@pass", s.toLegado())
        assertEquals(ProxyResolver.Spec("http", "h", 8080, "", ""), ProxyResolver.fromLegado("http://h:8080"))
        assertNull(ProxyResolver.fromLegado("direct"))
    }

    @Test
    fun parseListRejectsBadJson() {
        assertEquals(emptyList(), ProxyResolver.parseList(" "))
        assertNull(ProxyResolver.parseList("{oops"))
        assertEquals(listOf("a", "b"), ProxyResolver.list().map { it.name })
    }

    @Test
    fun matchesBySite() {
        assertEquals("203.0.113.4", ProxyResolver.resolve(null, "https://book.example.com/s")?.host)
        assertEquals("203.0.113.4", ProxyResolver.resolve(null, "https://a.b.example.org/")?.host)
        // 列表里排前面的代理先匹配
        assertEquals("203.0.113.4", ProxyResolver.resolve(null, "https://example.org/")?.host)
        assertEquals("203.0.113.8", ProxyResolver.resolve(null, "https://other.net/x")?.host)
        // 未列出的网站一律直连：精确主机不匹配子域名，后缀不匹配相似域名
        assertNull(ProxyResolver.resolve(null, "https://sub.other.net/"))
        assertNull(ProxyResolver.resolve(null, "https://www.example.com/"))
        assertNull(ProxyResolver.resolve(null, "https://badexample.org/"))
        assertNull(ProxyResolver.resolve(null, "https://example.org.evil.net/"))
        assertNull(ProxyResolver.resolve(null, "not a url"))
    }

    @Test
    fun precedence() {
        val url = "https://book.example.com/s"
        // 规则里用户自己写的 proxy 优先，且不限制网站
        val rule = ProxyResolver.resolve(source(null), url, "http://9.9.9.9:80")!!
        assertEquals("9.9.9.9", rule.host)
        assertNull(rule.sites)
        assertEquals("203.0.113.4", ProxyResolver.resolve(source(null), url, "")?.host)
        // direct 不使用管理员代理
        assertNull(ProxyResolver.resolve(source(ProxyResolver.MODE_DIRECT), url))
        // 旧版本留下的代理名称不再起作用，仍按网站匹配
        assertEquals("203.0.113.4", ProxyResolver.resolve(source("b"), url)?.host)
        assertNull(ProxyResolver.resolve(source("b"), "https://unlisted.com/"))
    }

    @Test
    fun adminProxyOnlyAllowsItsSites() {
        val spec = ProxyResolver.resolve(null, "https://book.example.com/s")!!
        assertTrue(spec.allows("https://book.example.com/next"))
        assertTrue(spec.allows("https://cdn.example.org/a.jpg"))
        // 跳转或请求到其他网站时不能使用这个代理
        assertFalse(spec.allows("https://other.net/"))
        assertFalse(spec.allows("http://169.254.169.254/"))
        assertTrue(spec.allows("data:text/plain,x"))
        // 浏览器里只额外放行 Cloudflare 验证码
        assertTrue(ProxyResolver.allowsInBrowser(spec.sites, "https://challenges.cloudflare.com/turnstile"))
        assertFalse(ProxyResolver.allowsInBrowser(spec.sites, "https://other.net/"))
        assertTrue(ProxyResolver.allowsInBrowser(null, "https://other.net/"))
    }

    @Test
    fun siteListParsing() {
        assertEquals(listOf("a.com", "*.b.org", "c.net"), ProxyResolver.siteList(" A.com,\n*.b.org.  c.net\r\n"))
        assertTrue(ProxyResolver.matches(listOf("a.com"), "A.COM."))
    }
}
