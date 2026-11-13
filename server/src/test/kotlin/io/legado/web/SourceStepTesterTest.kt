package io.legado.web

import io.legado.app.data.entities.BookSource
import io.legado.web.auth.UserPrincipal
import io.legado.web.service.ai.RepairStage
import io.legado.web.service.ai.SourceStepTester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceStepTesterTest {
    @Test
    fun engineDeadlineProducesTerminalTimeoutEvidence() = runBlocking {
        val evidence = SourceStepTester().check(UserPrincipal(1, "a", "admin"), BookSource(), null) {
            withTimeout(0) { error("deadline must cancel") }
        }
        assertEquals(RepairStage.search, evidence.stage)
        assertEquals("timeout", evidence.errorType)
        assertTrue(evidence.error.contains("90"), "deadline must explain the source execution limit")
    }

    @Test
    fun disconnectCancellationPropagatesWithoutFailureEvidence() = runBlocking {
        val cancellation = CancellationException("client disconnected")
        val error = assertFailsWith<CancellationException> {
            SourceStepTester().check(UserPrincipal(1, "a", "admin"), BookSource(), null) { throw cancellation }
        }
        assertEquals("client disconnected", error.message)
    }

    @Test
    fun parentDeadlinePropagatesToOperationBoundary() = runBlocking {
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100) {
                SourceStepTester().check(UserPrincipal(1, "a", "admin"), BookSource(), null) { awaitCancellation() }
            }
        }
    }
}
