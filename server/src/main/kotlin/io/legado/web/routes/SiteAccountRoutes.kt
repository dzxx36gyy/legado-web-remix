package io.legado.web.routes

import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.legado.web.auth.AUTH_JWT
import io.legado.web.auth.user
import io.legado.web.service.SiteAccountService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SiteDomainResponse(val domain: String)

/**
 * 网站账号（每个用户自己的登录状态）：
 * - GET    /api/site-accounts            已保存 Cookie 的网站
 * - DELETE /api/site-accounts?domain=    清除该网站的登录状态
 * - POST   /api/site-accounts/cookie     手动填写 Cookie { url, cookie }
 * - POST   /api/site-accounts/login      在远程浏览器中打开网址登录 { url, client }
 */
fun Route.siteAccountRoutes(service: SiteAccountService) {
    authenticate(AUTH_JWT) {
        route("/api/site-accounts") {
            get {
                call.respond(service.list(call.user.id))
            }
            delete {
                val domain = call.request.queryParameters["domain"].orEmpty()
                val uid = call.user.id
                withContext(Dispatchers.IO) { service.clear(uid, domain) }
                call.respond(OkResponse())
            }
            post("/cookie") {
                val req = call.receive<SiteAccountService.CookieRequest>()
                val uid = call.user.id
                call.respond(SiteDomainResponse(withContext(Dispatchers.IO) { service.setCookie(uid, req) }))
            }
            post("/login") {
                val req = call.receive<SiteAccountService.LoginRequest>()
                val uid = call.user.id
                withContext(Dispatchers.IO) { service.login(uid, req) }
                call.respond(OkResponse())
            }
        }
    }
}
