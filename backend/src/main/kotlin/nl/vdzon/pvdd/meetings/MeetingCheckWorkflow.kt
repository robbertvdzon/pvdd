package nl.vdzon.pvdd.meetings

import java.time.Clock
import java.time.Instant
import java.util.UUID
import nl.vdzon.pvdd.documents.DocumentIngestor
import nl.vdzon.pvdd.documents.DocumentReference
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

enum class MeetingCheckStatus {
    NO_FUTURE_MEETING,
    UNCHANGED,
    AGENDA_UNPUBLISHED,
    IMPORTED,
    PARTIAL,
    SOURCE_FAILURE,
    ALREADY_RUNNING,
    FAILED,
}

data class MeetingCheckResult(
    val status: MeetingCheckStatus,
    val meetingSourceId: String? = null,
    val errorCode: String? = null,
)

data class MeetingImportedEvent(
    val meetingId: UUID,
    val meetingSourceId: String,
    val occurredAt: Instant,
)

@Service
class MeetingCheckWorkflow(
    private val discovery: MeetingDiscoveryGateway,
    private val meetings: MeetingStore,
    private val documents: DocumentIngestor,
    private val locks: WorkflowLock,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun check(): MeetingCheckResult {
        val owner = UUID.randomUUID()
        if (!locks.tryAcquire(WORKFLOW_LOCK, owner)) return MeetingCheckResult(MeetingCheckStatus.ALREADY_RUNNING)
        var meetingId: UUID? = null
        return try {
            when (val outcome = discovery.discover(clock.instant())) {
                DiscoveryOutcome.NoFutureMeeting -> MeetingCheckResult(MeetingCheckStatus.NO_FUTURE_MEETING)
                is DiscoveryOutcome.SourceFailure -> MeetingCheckResult(
                    MeetingCheckStatus.SOURCE_FAILURE,
                    errorCode = outcome.code.name,
                )
                is DiscoveryOutcome.AgendaUnpublished -> {
                    val agenda = discovery.fetchAgenda(outcome.meeting.sourceUrl, enrichReports = false)
                    meetingId = saveMeeting(agenda, MeetingStatus.AGENDA_UNPUBLISHED, imported = false)
                    MeetingCheckResult(MeetingCheckStatus.AGENDA_UNPUBLISHED, agenda.sourceId)
                }
                is DiscoveryOutcome.Found -> {
                    if (meetings.lastSuccessfulSourceId() == outcome.meeting.sourceId) {
                        MeetingCheckResult(MeetingCheckStatus.UNCHANGED, outcome.meeting.sourceId)
                    } else {
                        val agenda = discovery.fetchAgenda(outcome.meeting.sourceUrl, enrichReports = true)
                        meetingId = saveMeeting(agenda, MeetingStatus.IMPORTING, imported = true)
                        importAgenda(meetingId!!, agenda)
                    }
                }
            }
        } catch (_: Exception) {
            meetingId?.let { meetings.markFailed(it, "IMPORT_FAILED") }
            MeetingCheckResult(MeetingCheckStatus.FAILED, errorCode = "IMPORT_FAILED")
        } finally {
            locks.release(WORKFLOW_LOCK, owner)
        }
    }

    private fun importAgenda(meetingId: UUID, agenda: ParsedMeetingAgenda): MeetingCheckResult {
        var fullyRead = true
        if (agenda.agendaDocuments.isNotEmpty()) {
            val itemId = meetings.upsert(
                AgendaItem(
                    id = UUID.randomUUID(),
                    meetingId = meetingId,
                    sourceId = "${agenda.sourceId}:meeting-documents",
                    parentSourceId = null,
                    sequence = 0,
                    displayNumber = null,
                    category = AgendaCategory.OTHER,
                    title = "Algemene vergaderdocumenten",
                    explanation = null,
                    treatmentProposal = null,
                    sourceUrl = agenda.sourceUrl,
                    sourceHash = AgendaParser.sha256(agenda.agendaDocuments.joinToString { it.sourceUrl.toString() }),
                    substantive = false,
                    importStatus = ImportStatus.IN_PROGRESS,
                ),
            )
            val summary = documents.ingest(itemId, agenda.agendaDocuments.map { it.toReference() })
            fullyRead = fullyRead && summary.fullyRead
        }

        agenda.items.forEach { parsed ->
            val initial = parsed.toAgendaItem(meetingId, ImportStatus.IN_PROGRESS)
            val itemId = meetings.upsert(initial)
            val summary = if (parsed.substantive) {
                documents.ingest(itemId, parsed.documents.map { it.toReference() })
            } else {
                null
            }
            val itemComplete = summary?.fullyRead ?: true
            fullyRead = fullyRead && itemComplete
            meetings.upsert(initial.copy(id = itemId, importStatus = if (itemComplete) ImportStatus.COMPLETE else ImportStatus.PARTIAL))
        }

        return if (fullyRead) {
            meetings.markAnalysing(meetingId)
            events.publishEvent(MeetingImportedEvent(meetingId, agenda.sourceId, clock.instant()))
            MeetingCheckResult(MeetingCheckStatus.IMPORTED, agenda.sourceId)
        } else {
            meetings.markPartial(meetingId, "DOCUMENTS_INCOMPLETE")
            MeetingCheckResult(MeetingCheckStatus.PARTIAL, agenda.sourceId, "DOCUMENTS_INCOMPLETE")
        }
    }

    private fun saveMeeting(agenda: ParsedMeetingAgenda, status: MeetingStatus, imported: Boolean): UUID = meetings.upsert(
        Meeting(
            id = UUID.randomUUID(),
            sourceId = agenda.sourceId,
            committee = agenda.committee,
            startsAt = agenda.startsAt,
            endsAt = agenda.endsAt,
            location = agenda.location,
            title = agenda.title,
            sourceUrl = agenda.sourceUrl,
            sourceHash = agenda.sourceHash,
            status = status,
            checkedAt = clock.instant(),
            importedAt = clock.instant().takeIf { imported },
        ),
    )

    private fun ParsedAgendaItem.toAgendaItem(meetingId: UUID, status: ImportStatus) = AgendaItem(
        id = UUID.randomUUID(),
        meetingId = meetingId,
        sourceId = sourceId,
        parentSourceId = parentSourceId,
        sequence = sequence,
        displayNumber = displayNumber,
        category = category,
        title = title,
        explanation = explanation,
        treatmentProposal = treatmentProposal,
        sourceUrl = sourceUrl,
        sourceHash = sourceHash,
        substantive = substantive,
        importStatus = status,
    )

    private fun ParsedDocumentLink.toReference() = DocumentReference(sourceId, name, sourceUrl)

    companion object {
        const val WORKFLOW_LOCK = "meeting-check"
    }
}
