package io.legado.web

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.legado.web.routes.respondNdjson
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NdjsonHeartbeatTest {
    @Test
    fun silentWaitEmitsHeartbeatsAndStopsAfterBlockEnds() = testApplication {
        routing {
            get("/slow") {
                call.respondNdjson(heartbeatMillis = 20) { emit ->
                    delay(300)
                    emit(mapOf("type" to "end"))
                }
            }
            get("/plain") { call.respondNdjson { emit -> delay(100); emit(mapOf("type" to "end")) } }
        }
        val slow = client.get("/slow").bodyAsText().trim().lines()
        assertTrue(slow.dropLast(1).isNotEmpty() && slow.dropLast(1).all { it == """{"type":"heartbeat"}""" }, slow.toString())
        assertEquals("""{"type":"end"}""", slow.last())
        assertEquals(listOf("""{"type":"end"}"""), client.get("/plain").bodyAsText().trim().lines())
    }
}
