package nl.vdzon.pvdd.meetings

import java.time.Clock
import java.time.Instant
import java.util.UUID
import nl.vdzon.pvdd.documents.DocumentIngestor
import nl.vdzon.pvdd.documents.DocumentReference
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.interceptor.TransactionAspectSupport

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
    val revisionNumber: Int? = null,
    val differences: Set<DifferenceType> = emptySet(),
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
    private val comparator: AgendaRevisionComparator = AgendaRevisionComparator(),
    private val revisions: SourceRevisionStore = TransientRevisionStore,
) {
    @Transactional
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
                    val baseline = revisions.baseline(agenda.sourceId)
                    meetingId = saveMeeting(
                        agenda,
                        MeetingStatus.AGENDA_UNPUBLISHED,
                        imported = false,
                        publicationStatus = PublicationStatus.PREVIEW,
                    )
                    val preview = savePreviewItems(meetingId, agenda)
                    val comparison = comparator.compare(agenda, PublicationStatus.PREVIEW, preview, baseline)
                    val stored = revisions.record(
                        meetingId,
                        agenda,
                        PublicationStatus.PREVIEW,
                        preview,
                        comparison,
                        clock.instant(),
                    )
                    MeetingCheckResult(
                        MeetingCheckStatus.AGENDA_UNPUBLISHED,
                        agenda.sourceId,
                        revisionNumber = stored.number,
                        differences = comparison.differences,
                    )
                }
                is DiscoveryOutcome.Found -> {
                    val agenda = discovery.fetchAgenda(outcome.meeting.sourceUrl, enrichReports = true)
                    val baseline = revisions.baseline(agenda.sourceId)
                    meetingId = saveMeeting(
                        agenda,
                        MeetingStatus.IMPORTING,
                        imported = true,
                        publicationStatus = PublicationStatus.CURRENT,
                    )
                    importAgenda(meetingId, agenda, baseline)
                }
            }
        } catch (_: Exception) {
            meetingId?.let { meetings.markFailed(it, "IMPORT_FAILED") }
            if (meetingId != null) markTransactionForRollback()
            MeetingCheckResult(MeetingCheckStatus.FAILED, errorCode = "IMPORT_FAILED")
        } finally {
            locks.release(WORKFLOW_LOCK, owner)
        }
    }

    private fun importAgenda(
        meetingId: UUID,
        agenda: ParsedMeetingAgenda,
        baseline: RevisionBaseline?,
    ): MeetingCheckResult {
        var fullyRead = true
        val revisionItems = mutableListOf<RevisionItem>()
        val meetingDocuments = mutableListOf<nl.vdzon.pvdd.documents.SourceDocument>()
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
            meetingDocuments += summary.documents
        }

        agenda.items.forEach { parsed ->
            val initial = parsed.toAgendaItem(meetingId, ImportStatus.IN_PROGRESS, SourceState.CURRENT)
            val itemId = meetings.upsert(initial)
            val summary = if (parsed.substantive) {
                documents.ingest(itemId, parsed.documents.map { it.toReference() })
            } else {
                null
            }
            val itemComplete = summary?.fullyRead ?: true
            fullyRead = fullyRead && itemComplete
            val revisionItem = comparator.currentItem(
                parsed,
                itemId,
                if (parsed.substantive) meetingDocuments + (summary?.documents ?: emptyList()) else emptyList(),
            )
            revisionItems += revisionItem
            meetings.upsert(
                initial.copy(
                    id = itemId,
                    importStatus = if (itemComplete) ImportStatus.COMPLETE else ImportStatus.PARTIAL,
                    currentFingerprint = revisionItem.fingerprint,
                ),
            )
        }
        val currentSourceIds = agenda.items.map(ParsedAgendaItem::sourceId).toMutableSet()
        if (agenda.agendaDocuments.isNotEmpty()) currentSourceIds += "${agenda.sourceId}:meeting-documents"
        meetings.markMissingItemsWithdrawn(meetingId, currentSourceIds)

        return if (fullyRead) {
            val comparison = comparator.compare(agenda, PublicationStatus.CURRENT, revisionItems, baseline)
            val stored = revisions.record(
                meetingId,
                agenda,
                PublicationStatus.CURRENT,
                revisionItems,
                comparison,
                clock.instant(),
            )
            if (comparison.unchanged) {
                meetings.markSuccessful(meetingId)
                MeetingCheckResult(
                    MeetingCheckStatus.UNCHANGED,
                    agenda.sourceId,
                    revisionNumber = stored.number,
                )
            } else if (comparison.requiresAnalysis) {
                meetings.markAnalysing(meetingId)
                events.publishEvent(MeetingImportedEvent(meetingId, agenda.sourceId, clock.instant()))
                MeetingCheckResult(
                    MeetingCheckStatus.IMPORTED,
                    agenda.sourceId,
                    revisionNumber = stored.number,
                    differences = comparison.differences,
                )
            } else {
                meetings.markSuccessful(meetingId)
                MeetingCheckResult(
                    MeetingCheckStatus.IMPORTED,
                    agenda.sourceId,
                    revisionNumber = stored.number,
                    differences = comparison.differences,
                )
            }
        } else {
            meetings.markPartial(meetingId, "DOCUMENTS_INCOMPLETE")
            markTransactionForRollback()
            MeetingCheckResult(MeetingCheckStatus.PARTIAL, agenda.sourceId, "DOCUMENTS_INCOMPLETE")
        }
    }

    private fun saveMeeting(
        agenda: ParsedMeetingAgenda,
        status: MeetingStatus,
        imported: Boolean,
        publicationStatus: PublicationStatus,
    ): UUID = meetings.upsert(
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
            publicationStatus = publicationStatus,
        ),
    )

    private fun savePreviewItems(meetingId: UUID, agenda: ParsedMeetingAgenda): List<RevisionItem> {
        val itemIds = agenda.items
            .filter { it.substantive && it.category == AgendaCategory.C }
            .associate { parsed ->
                parsed.sourceId to meetings.upsert(
                    parsed.toAgendaItem(meetingId, ImportStatus.PENDING, SourceState.PREVIEW),
                )
            }
        val preview = comparator.previewItems(agenda, itemIds)
        preview.forEach { revisionItem ->
            val parsed = requireNotNull(agenda.items.firstOrNull { it.sourceId == revisionItem.sourceId })
            meetings.upsert(
                parsed.toAgendaItem(meetingId, ImportStatus.PENDING, SourceState.PREVIEW)
                    .copy(id = revisionItem.agendaItemId, currentFingerprint = revisionItem.fingerprint),
            )
        }
        return preview
    }

    private fun ParsedAgendaItem.toAgendaItem(
        meetingId: UUID,
        status: ImportStatus,
        sourceState: SourceState,
    ) = AgendaItem(
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
        sourceState = sourceState,
    )

    private fun ParsedDocumentLink.toReference() = DocumentReference(sourceId, name, sourceUrl)

    companion object {
        const val WORKFLOW_LOCK = "meeting-check"
    }

    private object TransientRevisionStore : SourceRevisionStore {
        override fun baseline(meetingSourceId: String): RevisionBaseline? = null

        override fun record(
            meetingId: UUID,
            agenda: ParsedMeetingAgenda,
            publicationStatus: PublicationStatus,
            items: List<RevisionItem>,
            comparison: RevisionComparison,
            checkedAt: Instant,
        ) = StoredRevision(UUID.randomUUID(), 1, comparison)
    }

    private fun markTransactionForRollback() {
        runCatching { TransactionAspectSupport.currentTransactionStatus().setRollbackOnly() }
    }
}
