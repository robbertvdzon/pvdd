package nl.vdzon.pvdd.analysis

import java.net.URI
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

enum class Urgency { LOW, MEDIUM, HIGH }

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
)

data class Citation(
    val sourceId: String,
    val sourceType: CitationSourceType,
    val sourceUrl: URI,
    val pageNumber: Int?,
    val section: String?,
    val quote: String,
)

enum class CitationSourceType { MEETING_DOCUMENT, POLICY_PROGRAMME }

data class AdviceSection(
    val text: String,
    val citations: List<Citation>,
)

data class AbAdvice(
    val agendaItemSourceId: String,
    val subject: AdviceSection,
    val position: AdviceSection,
    val committeeAction: AdviceSection,
    val pointsForExecutive: AdviceSection,
    val technicalQuestions: AdviceSection,
)

data class CAdvice(
    val agendaItemSourceId: String,
    val moveToB: Boolean,
    val motivation: AdviceSection,
    val urgency: Urgency,
    val committeeGoal: AdviceSection,
    val keyQuestion: AdviceSection?,
)
