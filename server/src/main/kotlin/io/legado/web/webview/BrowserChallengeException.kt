package io.legado.web.webview

class BrowserChallengeException(val status: Int, val url: String, usingCloak: Boolean) : RuntimeException(
    if (usingCloak) "已使用 Cloak 访问，但网站仍返回 HTTP $status"
    else "默认浏览器访问网站返回 HTTP $status"
)

internal fun cloakSwitchStatus(status: Int) = status == 401 || status == 403 || status == 418
