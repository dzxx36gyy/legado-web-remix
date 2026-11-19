package io.legado.web.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.auth.authenticate
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.writeFully
import com.google.gson.Gson
import io.legado.web.auth.AUTH_JWT
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.user
import io.legado.web.service.tts.ContinuousRequest
import io.legado.web.service.tts.ContinuousSpeech
import kotlinx.coroutines.delay

data class SpeechPauseRequest(val paused: Boolean)

fun Route.continuousTtsRoutes(speech: ContinuousSpeech) {
    val gson = Gson()
    authenticate(AUTH_JWT) {
        route("/api/tts/continuous") {
            install(HttpRequestLifecycle) { cancelCallOnClose = true }
            post { call.respond(mapOf("id" to speech.create(call.user, call.receive<ContinuousRequest>()))) }
            route("/{session}") {
                fun io.ktor.server.application.ApplicationCall.sessionId() = parameters["session"] ?: throw BadRequestException("缺少朗读会话")
                delete { speech.stop(call.user, call.sessionId()); call.respond(OkResponse()) }
                post("/pause") {
                    speech.pause(call.user, call.sessionId(), call.receive<SpeechPauseRequest>().paused)
                    call.respond(OkResponse())
                }
                get("/events") {
                    val user = call.user
                    val id = call.sessionId()
                    speech.status(user, id, 0)
                    call.response.header(HttpHeaders.CacheControl, "private, no-store")
                    call.response.header("X-Accel-Buffering", "no")
                    call.respondTextWriter(ContentType.parse("application/x-ndjson")) {
                        while (true) {
                            val status = speech.status(user, id, 0)
                            write(gson.toJson(status) + "\n")
                            flush()
                            if (status.done) break
                            delay(500)
                        }
                    }
                }
                get("/audio") {
                    val user = call.user
                    val id = call.sessionId()
                    speech.status(user, id, 0)
                    call.response.header(HttpHeaders.CacheControl, "private, no-store")
                    call.response.header("X-Content-Type-Options", "nosniff")
                    call.response.header("X-Accel-Buffering", "no")
                    call.response.header(HttpHeaders.AcceptRanges, "none")
                    call.respondBytesWriter(ContentType.Audio.MPEG) {
                        speech.audio(user, id) { writeFully(it); flush() }
                    }
                }
            }
        }
    }
}
