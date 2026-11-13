package io.legado.web

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.gson.JsonParser
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.legado.web.auth.AUTH_JWT
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.TooManyRequestsException
import io.legado.web.routes.pwaIconRoutes
import io.legado.web.service.PwaIconService
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PwaIconRoutesTest {
    private val algorithm = Algorithm.HMAC256("pwa-icon-test-secret")
    private val token = JWT.create().withSubject("1").sign(algorithm)

    private fun Application.icons(dir: File) {
        install(ContentNegotiation) { gson() }
        install(CacheHeaders)
        install(Authentication) {
            jwt(AUTH_JWT) {
                verifier(JWT.require(algorithm).build())
                validate { JWTPrincipal(it.payload) }
            }
        }
        install(StatusPages) {
            exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest) }
            exception<NotFoundException> { call, _ -> call.respond(HttpStatusCode.NotFound) }
            exception<TooManyRequestsException> { call, _ -> call.respond(HttpStatusCode.TooManyRequests) }
        }
        routing { pwaIconRoutes(PwaIconService(dir)) }
    }

    private fun png(side: Int = 512): ByteArray = ByteArrayOutputStream().use { output ->
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xff123456.toInt())
        assertTrue(ImageIO.write(image, "PNG", output))
        image.flush()
        output.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val body = type.toByteArray(Charsets.US_ASCII) + data
        return ByteBuffer.allocate(body.size + 8).putInt(data.size).put(body)
            .putInt(CRC32().apply { update(body) }.value.toInt()).array()
    }

    @Test
    fun uploadPublicReadMetadataRemovalAndRestartPersistence() {
        val dir = Files.createTempDirectory("pwa-icons-test").toFile()
        try {
            val original = png()
            val secret = "private-upload-metadata"
            val input = original.copyOfRange(0, 33) + chunk("tEXt", "Comment\u0000$secret".toByteArray()) +
                original.copyOfRange(33, original.size) + "trailing-secret".toByteArray()
            var id = ""
            var stored = byteArrayOf()
            testApplication {
                application { icons(dir) }
                val response = client.post("/api/pwa-icons") {
                    bearerAuth(token)
                    contentType(ContentType.Image.PNG)
                    setBody(input)
                }
                assertEquals(HttpStatusCode.OK, response.status)
                id = JsonParser.parseString(response.bodyAsText()).asJsonObject["id"].asString
                assertTrue(Regex("[0-9a-f]{64}").matches(id))
                val icon = client.get("/api/pwa-icons/$id/icon.png")
                assertEquals(HttpStatusCode.OK, icon.status)
                assertEquals("image/png", icon.headers[HttpHeaders.ContentType]?.substringBefore(';'))
                assertEquals("nosniff", icon.headers["X-Content-Type-Options"])
                assertEquals("sandbox; default-src 'none'", icon.headers["Content-Security-Policy"])
                assertEquals("public, max-age=31536000, immutable", icon.headers[HttpHeaders.CacheControl])
                stored = icon.bodyAsBytes()
                assertFalse(String(stored, Charsets.ISO_8859_1).contains(secret))
                assertFalse(String(stored, Charsets.ISO_8859_1).contains("trailing-secret"))
                assertEquals(id, MessageDigest.getInstance("SHA-256").digest(stored).joinToString("") { "%02x".format(it) })
                val decoded = ImageIO.read(stored.inputStream())
                assertEquals(512, decoded.width)
                assertEquals(512, decoded.height)
                assertEquals(0xff123456.toInt(), decoded.getRGB(0, 0))
                val repeated = client.post("/api/pwa-icons") {
                    bearerAuth(token); contentType(ContentType.Image.PNG); setBody(original)
                }
                assertEquals(id, JsonParser.parseString(repeated.bodyAsText()).asJsonObject["id"].asString)
            }
            testApplication {
                application { icons(dir) }
                assertContentEquals(stored, client.get("/api/pwa-icons/$id/icon.png").bodyAsBytes())
                val response = client.get("/api/pwa-icons/$id/manifest.webmanifest")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("application/manifest+json", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
                assertEquals("nosniff", response.headers["X-Content-Type-Options"])
                val manifest = JsonParser.parseString(response.bodyAsText()).asJsonObject
                for (key in listOf("id", "start_url", "scope")) assertEquals("/", manifest[key].asString)
                assertEquals("/api/pwa-icons/$id/icon.png", manifest["icons"].asJsonArray[0].asJsonObject["src"].asString)
                assertFalse(response.bodyAsText().contains(token))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnauthenticatedMalformedOversizedAndMissingResources() = testApplication {
        val dir = Files.createTempDirectory("pwa-icons-invalid").toFile()
        try {
            application { icons(dir) }
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/pwa-icons") { setBody(png()) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/pwa-icons") { bearerAuth("bad"); setBody(png()) }.status)
            val hugeDimensions = png().let {
                it.copyOfRange(0, 8) + chunk("IHDR", ByteBuffer.allocate(13).putInt(Int.MAX_VALUE)
                    .putInt(Int.MAX_VALUE).put(byteArrayOf(8, 6, 0, 0, 0)).array()) + it.copyOfRange(33, it.size)
            }
            for (bytes in listOf("<svg onload='alert(1)'/>".toByteArray(), png(16), png().copyOf(40), hugeDimensions,
                ByteArray(PwaIconService.MAX_BYTES + 1))) {
                assertEquals(HttpStatusCode.BadRequest, client.post("/api/pwa-icons") {
                    bearerAuth(token); contentType(ContentType.Image.PNG); setBody(bytes)
                }.status)
            }
            assertEquals(HttpStatusCode.UnsupportedMediaType, client.post("/api/pwa-icons") {
                bearerAuth(token); contentType(ContentType.Text.Html); setBody(png())
            }.status)
            for (id in listOf("invalid", "a".repeat(64), "A".repeat(64), "%2e%2e")) {
                for (resource in listOf("icon.png", "manifest.webmanifest")) {
                    assertEquals(HttpStatusCode.NotFound, client.get("/api/pwa-icons/$id/$resource").status)
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun capacitySurvivesRestartAndStillAllowsExistingContent() = testApplication {
        val dir = Files.createTempDirectory("pwa-icons-quota").toFile()
        try {
            val root = File(dir, "pwa-icons").apply { mkdirs() }
            repeat(PwaIconService.MAX_ICONS) { File(root, "%064x.png".format(it)).writeBytes(byteArrayOf()) }
            application { icons(dir) }
            val input = png()
            assertEquals(HttpStatusCode.TooManyRequests, client.post("/api/pwa-icons") {
                bearerAuth(token); contentType(ContentType.Image.PNG); setBody(input)
            }.status)
            val id = MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { "%02x".format(it) }
            File(root, "%064x.png".format(0)).renameTo(File(root, "$id.png"))
            assertEquals(HttpStatusCode.OK, client.post("/api/pwa-icons") {
                bearerAuth(token); contentType(ContentType.Image.PNG); setBody(input)
            }.status)
        } finally {
            dir.deleteRecursively()
        }
    }
}
