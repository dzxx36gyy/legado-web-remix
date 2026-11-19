package io.legado.web.webview

class BrowserChallengeException(val status: Int, val url: String) : RuntimeException("默认浏览器访问网站返回 HTTP $status，可切换到 Cloak 重试")

internal fun cloakSwitchStatus(status: Int) = status == 401 || status == 403 || status == 418
