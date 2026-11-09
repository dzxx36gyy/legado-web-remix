package io.legado.web.routes

import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.legado.web.auth.AUTH_JWT
import io.legado.web.auth.user
import io.legado.web.service.SubscriptionService

fun Route.ruleSubscriptionRoutes(services: Map<String, SubscriptionService>) {
    authenticate(AUTH_JWT) {
        services.forEach { (kind, service) ->
            route("/api/rule-subscriptions/$kind") {
                get { call.respond(service.list(call.user)) }
                post {
                    val req = call.receive<SubscriptionRequest>()
                    call.respond(mapOf("id" to service.create(call.user, req.name, req.url, false, req.autoUpdate)))
                }
                put("/{id}") {
                    service.update(call.user, call.longParam("id"), call.receive<SubscriptionRequest>())
                    call.respond(OkResponse())
                }
                post("/{id}/refresh") { call.respond(service.refresh(call.user, call.longParam("id"))) }
                delete("/{id}") { service.delete(call.user, call.longParam("id")); call.respond(OkResponse()) }
            }
        }
    }
}
