package io.legado.web.routes

import io.ktor.server.auth.authenticate
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.legado.web.auth.AUTH_JWT
import io.legado.web.auth.user
import io.legado.web.service.ai.AiChatClient
import io.legado.web.service.ai.AiModelInput
import io.legado.web.service.ai.AiModelService
import io.legado.web.service.ai.AiRepairService
import io.legado.web.service.ai.RepairRequest
import io.legado.web.service.ai.CreateSourceRequest
import io.legado.web.service.ai.RepairChallengeException
import kotlinx.coroutines.CancellationException
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.ForbiddenException
import io.legado.web.service.ai.AiRepairPayload
import io.legado.web.service.ai.FetchModelsRequest

fun Route.aiRoutes(models: AiModelService, repair: AiRepairService) {
    authenticate(AUTH_JWT) {
        route("/api/ai/models") {
            get { call.respond(models.list(call.user)) }
            post { call.respond(models.save(call.user, null, call.receive<AiModelInput>())) }
            post("/fetch-list") { call.respond(models.fetchModels(call.user, call.receive<FetchModelsRequest>())) }
            put("/{id}") { call.respond(models.save(call.user, call.longParam("id"), call.receive<AiModelInput>())) }
            delete("/{id}") {
                models.delete(call.user, call.longParam("id"))
                call.respond(OkResponse())
            }
            route("/{id}/test") {
                install(HttpRequestLifecycle) { cancelCallOnClose = true }
                post {
                    AiChatClient().complete(models.usable(call.user, call.longParam("id")), "Reply with OK.")
                    call.respond(OkResponse())
                }
            }
        }
        route("/api/sources/ai-repair") {
            install(HttpRequestLifecycle) { cancelCallOnClose = true }
            post {
                val req = call.receive<RepairRequest>()
                val user = call.user
                repair.authorize(user, req.sourceId, req.modelId)
                call.respondNdjson(heartbeatMillis = 15_000) { emit ->
                    try {
                        repair.repair(user, req, emit)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RepairChallengeException) {
                        emit(mapOf("type" to "error", "code" to "challenge", "message" to e.message))
                    } catch (e: Exception) {
                        call.application.environment.log.warn("AI 修复失败", e)
                        emit(mapOf("type" to "error", "message" to safeAiError(e, "AI 修复失败，请检查模型设置或重试")))
                    }
                }
            }
        }
        route("/api/sources/ai-create") {
            install(HttpRequestLifecycle) { cancelCallOnClose = true }
            post {
                val req = call.receive<CreateSourceRequest>()
                val user = call.user
                models.usable(user, req.modelId)
                call.respondNdjson(heartbeatMillis = 15_000) { emit ->
                    try {
                        repair.create(user, req, emit)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RepairChallengeException) {
                        emit(mapOf("type" to "error", "code" to "challenge", "message" to e.message))
                    } catch (e: Exception) {
                        call.application.environment.log.warn("AI 创建失败", e)
                        emit(mapOf("type" to "error", "message" to safeAiError(e, "AI 创建失败，请检查模型设置或网站搜索入口")))
                    }
                }
            }
        }
    }
}

private fun safeAiError(e: Exception, fallback: String): String =
    if (e is BadRequestException || e is ForbiddenException) AiRepairPayload.redact(e.message.orEmpty()).ifBlank { fallback }
    else "$fallback（${e.javaClass.simpleName}）"
