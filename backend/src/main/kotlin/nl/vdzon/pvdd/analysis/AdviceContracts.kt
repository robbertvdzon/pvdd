package nl.vdzon.pvdd.analysis

import java.time.Instant
import java.util.UUID

enum class AnalysisStatus {
    PENDING,
    QUEUED,
    WAITING_FOR_WORKER,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

enum class AnalysisRunType { FINAL_ADVICE, SOURCE_NOTES }

data class AnalysisRun(
    val id: UUID,
    val agendaItemId: UUID,
    val sourceFingerprint: String,
    val promptVersion: String,
    val selectionVersion: String,
    val idempotencyKey: String,
    val runtimeJobId: String?,
    val status: AnalysisStatus,
    val errorCode: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
    val runtimeAttemptCount: Int = 0,
    val nextRuntimeAttemptAt: Instant? = null,
    val retryOfRunId: UUID? = null,
)

enum class CitationSourceType { MEETING_DOCUMENT, POLICY_PROGRAMME, POLICY_POSITIONS }
