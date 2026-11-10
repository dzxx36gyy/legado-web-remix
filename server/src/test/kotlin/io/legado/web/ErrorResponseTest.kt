package io.legado.web

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.legado.app.utils.GSON
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.ForbiddenException
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.UnauthorizedException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorResponseTest {
    @Test
    fun errorsKeepStatusAndJsonForNdjsonAccept() = checkErrors(listOf("application/x-ndjson", "*/*", "application/json"))

    @Test
    fun errorsKeepStatusAndJsonForEventStreamAccept() = checkErrors(listOf("text/event-stream"))

    private fun checkErrors(accepts: List<String>) = try {
        testApplication {
            val dir = Files.createTempDirectory("error-response-test-").toFile().apply { deleteOnExit() }
            environment { config = MapApplicationConfig("legado.dataDir" to dir.absolutePath) }
            val errors = listOf(
                HttpStatusCode.Forbidden to ForbiddenException("forbidden"),
                HttpStatusCode.BadRequest to BadRequestException("bad request"),
                HttpStatusCode.NotFound to NotFoundException("not found"),
                HttpStatusCode.Unauthorized to UnauthorizedException("unauthorized"),
            )
            application {
                module()
                routing {
                    errors.forEach { (status, error) -> get("/test-error/${status.value}") { throw error } }
                }
            }
            for (accept in accepts) {
                for ((status, error) in errors) {
                    val response = client.get("/test-error/${status.value}") { header(HttpHeaders.Accept, accept) }
                    assertEquals(status, response.status, "Accept: $accept")
                    assertEquals(ContentType.Application.Json, ContentType.parse(response.headers[HttpHeaders.ContentType]!!).withoutParameters())
                    assertEquals(error.message, GSON.fromJson(response.bodyAsText(), ErrorResponse::class.java).error)
                }
            }
        }
    } finally {
        io.legado.web.security.NetworkGuard.resetForTests()
    }
}
