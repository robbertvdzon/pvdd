package nl.vdzon.pvdd.meetings

import java.net.URI
import java.time.Instant
import java.util.UUID

enum class MeetingStatus {
    DISCOVERED,
    AGENDA_UNPUBLISHED,
    IMPORTING,
    ANALYSING,
    COMPLETE,
    PARTIAL,
    FAILED,
}

enum class AgendaCategory { A, B, C, OTHER }

enum class ImportStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETE,
    PARTIAL,
    FAILED,
}

data class Meeting(
    val id: UUID,
    val sourceId: String,
    val committee: String,
    val startsAt: Instant,
    val endsAt: Instant?,
    val location: String?,
    val title: String,
    val sourceUrl: URI,
    val sourceHash: String,
    val status: MeetingStatus,
    val checkedAt: Instant,
    val importedAt: Instant?,
    val publicationStatus: PublicationStatus = PublicationStatus.CURRENT,
    val currentRevisionNumber: Int = 0,
    val canonicalFingerprint: String? = null,
)

data class AgendaItem(
    val id: UUID,
    val meetingId: UUID,
    val sourceId: String,
    val parentSourceId: String?,
    val sequence: Int,
    val displayNumber: String?,
    val category: AgendaCategory,
    val title: String,
    val explanation: String?,
    val treatmentProposal: String?,
    val sourceUrl: URI,
    val sourceHash: String,
    val substantive: Boolean,
    val importStatus: ImportStatus,
    val sourceState: SourceState = SourceState.CURRENT,
    val currentFingerprint: String? = null,
)

sealed interface DiscoveryOutcome {
    data class Found(val meeting: DiscoveredMeeting) : DiscoveryOutcome
    data object NoFutureMeeting : DiscoveryOutcome
    data class AgendaUnpublished(val meeting: DiscoveredMeeting) : DiscoveryOutcome
    data class SourceFailure(val code: SourceErrorCode) : DiscoveryOutcome
}

data class DiscoveredMeeting(
    val sourceId: String,
    val startsAt: Instant,
    val sourceUrl: URI,
)

enum class SourceErrorCode {
    CONNECT_TIMEOUT,
    READ_TIMEOUT,
    HTTP_ERROR,
    UNKNOWN_HTML,
    DISALLOWED_REDIRECT,
}
