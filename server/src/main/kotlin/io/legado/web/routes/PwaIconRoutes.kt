package io.legado.web.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import io.legado.web.auth.AUTH_JWT
import io.legado.web.auth.BadRequestException
import io.legado.web.service.PwaIconService
import java.io.ByteArrayOutputStream

fun Route.pwaIconRoutes(service: PwaIconService) {
    authenticate(AUTH_JWT) {
        post("/api/pwa-icons") {
            if (call.request.contentType().withoutParameters() != ContentType.Image.PNG) {
                call.respond(HttpStatusCode.UnsupportedMediaType)
                return@post
            }
            val max = PwaIconService.MAX_BYTES
            if ((call.request.contentLength() ?: 0) > max) throw BadRequestException("Icon exceeds 2 MiB")
            val channel = call.receiveChannel()
            val bytes = ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = channel.readAvailable(buffer, 0, minOf(buffer.size, max - output.size() + 1))
                    if (n < 0) break
                    if (output.size() + n > max) throw BadRequestException("Icon exceeds 2 MiB")
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(service.add(bytes))
        }
    }
    get("/api/pwa-icons/{id}/icon.png") {
        val file = service.get(call.parameters["id"].orEmpty())
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Content-Security-Policy", "sandbox; default-src 'none'")
        call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
        call.respondFile(file)
    }
    get("/api/pwa-icons/{id}/manifest.webmanifest") {
        val id = service.get(call.parameters["id"].orEmpty()).nameWithoutExtension
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
        call.respondText(
            """{"id":"/","name":"阅读","short_name":"阅读","start_url":"/","scope":"/","display":"standalone","background_color":"#ffffff","theme_color":"#ffffff","icons":[{"src":"/api/pwa-icons/$id/icon.png","sizes":"512x512","type":"image/png","purpose":"any"}]}""",
            ContentType.parse("application/manifest+json"),
        )
    }
}
