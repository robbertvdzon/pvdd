package nl.vdzon.pvdd.analysis

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalysisRetryTest {
    @Test
    fun `transient runtime failures use bounded increasing backoff`() {
        assertEquals(Duration.ofMinutes(15), automaticRetryDelay("ENGINE_FAILED", 1))
        assertEquals(Duration.ofMinutes(120), automaticRetryDelay("ENGINE_FAILED", 2))
        assertNull(automaticRetryDelay("ENGINE_FAILED", 3))
        assertNull(automaticRetryDelay("INVALID_RESULT", 1))
    }

    @Test
    fun `each technical execution receives a distinct idempotency key`() {
        val run = AnalysisRun(
            id = UUID.randomUUID(),
            agendaItemId = UUID.randomUUID(),
            sourceFingerprint = "a".repeat(64),
            promptVersion = "prompt-v1",
            selectionVersion = "selection-v1",
            idempotencyKey = "pvdd-${"b".repeat(64)}",
            runtimeJobId = null,
            status = AnalysisStatus.PENDING,
            errorCode = null,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            completedAt = null,
        )

        assertEquals("${run.idempotencyKey}-execution-1", runtimeExecutionKey(run))
        assertEquals("${run.idempotencyKey}-execution-3", runtimeExecutionKey(run.copy(runtimeAttemptCount = 2)))
    }
}
