package nl.vdzon.pvdd.meetings

import java.time.Clock
import java.time.Instant
import java.util.UUID
import nl.vdzon.pvdd.documents.DocumentIngestor
import nl.vdzon.pvdd.documents.DocumentReference
import nl.vdzon.pvdd.source.Commissie
import nl.vdzon.pvdd.source.CommissieSource
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.slf4j.LoggerFactory

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
    val meetings: List<MeetingCheckMeeting> = emptyList(),
    /** Additief: uitkomst per commissie in configuratievolgorde; de velden hierboven blijven die van Ruimte. */
    val commissies: List<CommissieCheckResult> = emptyList(),
)

data class CommissieCheckResult(
    val commissie: Commissie,
    val naam: String,
    val success: Boolean,
    val status: MeetingCheckStatus,
    val errorCode: String? = null,
    val meetingSourceId: String? = null,
    val meetings: List<MeetingCheckMeeting> = emptyList(),
)

enum class MeetingCheckOutcome {
    UNCHANGED,
    NEW_DOCUMENTS,
    AGENDA_UNPUBLISHED,
    FAILED,
}

data class MeetingCheckMeeting(
    val meetingId: UUID?,
    val meetingSourceId: String?,
    val title: String?,
    val startsAt: Instant?,
    val location: String?,
    val outcome: MeetingCheckOutcome,
    val newAnalyses: Int,
    val errorDescription: String?,
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
    transactionManager: PlatformTransactionManager? = null,
) {
    // Eén transactie per commissie; een vergadering daarbinnen is een savepoint, zodat een falende
    // vergadering alleen zichzelf terugdraait en een falende commissie al haar schrijfacties.
    private val commissieTransactions: TransactionTemplate? = transactionManager?.let {
        TransactionTemplate(it).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
    }
    private val transactions: TransactionTemplate? = transactionManager?.let {
        TransactionTemplate(it).apply { propagationBehavior = TransactionDefinition.PROPAGATION_NESTED }
    }

    fun check(): MeetingCheckResult {
        val owner = UUID.randomUUID()
        if (!locks.tryAcquire(WORKFLOW_LOCK, owner)) return MeetingCheckResult(MeetingCheckStatus.ALREADY_RUNNING)
        return (try {
            val now = clock.instant()
            val configured = discovery.commissies().ifEmpty {
                listOf(CommissieSource(Commissie.RUIMTE, Commissie.RUIMTE.defaultName, ""))
            }
            val runs = configured.map { source -> source to checkCommissie(source, now) }
            val primary = runs.firstOrNull { it.first.commissie == Commissie.RUIMTE } ?: runs.first()
            primary.second.copy(
                commissies = runs.map { (source, result) ->
                    CommissieCheckResult(
                        commissie = source.commissie,
                        naam = source.weergavenaam,
                        success = result.status != MeetingCheckStatus.SOURCE_FAILURE &&
                            result.status != MeetingCheckStatus.FAILED,
                        status = result.status,
                        errorCode = result.errorCode,
                        meetingSourceId = result.meetingSourceId,
                        meetings = result.meetings,
                    )
                },
            )
        } catch (failure: Exception) {
            log.warn("Meeting check failed with {}", safeCode(failure), failure)
            MeetingCheckResult(MeetingCheckStatus.FAILED, errorCode = "IMPORT_FAILED")
        } finally {
            locks.release(WORKFLOW_LOCK, owner)
        }).also(::logResult)
    }

    /** Verwerkt één commissie in een eigen transactie; een fout blijft beperkt tot deze commissie. */
    private fun checkCommissie(source: CommissieSource, now: Instant): MeetingCheckResult = try {
        commissieTransactions?.execute { runCommissie(source.commissie, now) } ?: runCommissie(source.commissie, now)
    } catch (failure: Exception) {
        log.warn("Meeting check for commissie {} failed with {}", source.weergavenaam, safeCode(failure), failure)
        MeetingCheckResult(MeetingCheckStatus.FAILED, errorCode = "IMPORT_FAILED")
    }

    private fun runCommissie(commissie: Commissie, now: Instant): MeetingCheckResult {
        val outcomes = if (commissie == Commissie.RUIMTE) discovery.discover(now) else discovery.discoverCommissie(commissie, now)
        val single = outcomes.singleOrNull()
        return if (single == DiscoveryOutcome.NoFutureMeeting) {
            MeetingCheckResult(MeetingCheckStatus.NO_FUTURE_MEETING)
        } else if (single is DiscoveryOutcome.SourceFailure) {
            log.warn("Source of commissie {} failed with {}", commissie.defaultName, single.code)
            MeetingCheckResult(MeetingCheckStatus.SOURCE_FAILURE, errorCode = single.code.name)
        } else {
            val processed = outcomes.mapIndexedNotNull { index, outcome -> processIsolated(commissie, outcome, index > 0) }
            val first = processed.first()
            first.result.copy(meetings = processed.map { it.entry })
        }
    }

    private class Processed(val result: MeetingCheckResult, val entry: MeetingCheckMeeting)

    /** Handles one meeting in its own transaction; a failure rolls back only this meeting. */
    private fun processIsolated(commissie: Commissie, outcome: DiscoveryOutcome, extra: Boolean): Processed? {
        if (outcome == DiscoveryOutcome.NoFutureMeeting) return null
        return try {
            transactions?.execute { process(commissie, outcome, extra) } ?: process(commissie, outcome, extra)
        } catch (failure: Exception) {
            log.warn("Meeting import for commissie {} failed with {}", commissie.defaultName, safeCode(failure), failure)
            val known = when (outcome) {
                is DiscoveryOutcome.Found -> outcome.meeting
                is DiscoveryOutcome.AgendaUnpublished -> outcome.meeting
                else -> null
            }
            Processed(
                MeetingCheckResult(MeetingCheckStatus.FAILED, errorCode = "IMPORT_FAILED"),
                MeetingCheckMeeting(
                    meetingId = null,
                    meetingSourceId = known?.sourceId,
                    title = null,
                    startsAt = known?.startsAt,
                    location = null,
                    outcome = MeetingCheckOutcome.FAILED,
                    newAnalyses = 0,
                    errorDescription = FAILURE_DESCRIPTION,
                ),
            )
        }
    }

    private fun process(commissie: Commissie, outcome: DiscoveryOutcome, extra: Boolean): Processed {
        var meetingId: UUID? = null
        var analyses = 0
        fun entry(agenda: ParsedMeetingAgenda, result: MeetingCheckResult) = MeetingCheckMeeting(
            meetingId = meetingId,
            meetingSourceId = agenda.sourceId,
            title = agenda.title,
            startsAt = agenda.startsAt,
            location = agenda.location,
            outcome = when {
                result.status == MeetingCheckStatus.AGENDA_UNPUBLISHED -> MeetingCheckOutcome.AGENDA_UNPUBLISHED
                result.status == MeetingCheckStatus.UNCHANGED -> MeetingCheckOutcome.UNCHANGED
                result.differences.isEmpty() -> MeetingCheckOutcome.UNCHANGED
                else -> MeetingCheckOutcome.NEW_DOCUMENTS
            },
            newAnalyses = analyses,
            errorDescription = null,
        )
        return when (outcome) {
            DiscoveryOutcome.NoFutureMeeting -> error("not processable")
            is DiscoveryOutcome.SourceFailure -> Processed(
                MeetingCheckResult(MeetingCheckStatus.SOURCE_FAILURE, errorCode = outcome.code.name),
                MeetingCheckMeeting(
                    null, null, null, null, null, MeetingCheckOutcome.FAILED, 0, FAILURE_DESCRIPTION,
                ),
            )
            is DiscoveryOutcome.AgendaUnpublished -> {
                if (extra) {
                    return Processed(
                        MeetingCheckResult(MeetingCheckStatus.AGENDA_UNPUBLISHED, outcome.meeting.sourceId),
                        MeetingCheckMeeting(
                            null, outcome.meeting.sourceId, null, outcome.meeting.startsAt, null,
                            MeetingCheckOutcome.AGENDA_UNPUBLISHED, 0, null,
                        ),
                    )
                }
                val agenda = discovery.fetchAgenda(outcome.meeting.sourceUrl, enrichReports = true)
                val baseline = revisions.baseline(agenda.sourceId)
                val result = if (agenda.items.none { it.substantive && it.category in ANALYSIS_CATEGORIES }) {
                    meetingId = saveMeeting(
                        agenda,
                        MeetingStatus.AGENDA_UNPUBLISHED,
                        imported = false,
                        publicationStatus = PublicationStatus.PREVIEW,
                        commissie = commissie,
                    )
                    val comparison = comparator.compare(agenda, PublicationStatus.PREVIEW, emptyList(), baseline)
                    val stored = revisions.record(
                        meetingId,
                        agenda,
                        PublicationStatus.PREVIEW,
                        emptyList(),
                        comparison,
                        clock.instant(),
                    )
                    MeetingCheckResult(
                        MeetingCheckStatus.AGENDA_UNPUBLISHED,
                        agenda.sourceId,
                        revisionNumber = stored.number,
                        differences = comparison.differences,
                    )
                } else {
                    meetingId = saveMeeting(
                        agenda,
                        MeetingStatus.IMPORTING,
                        imported = true,
                        publicationStatus = PublicationStatus.PREVIEW,
                        commissie = commissie,
                    )
                    importAgenda(meetingId, agenda, baseline, PublicationStatus.PREVIEW, SourceState.PREVIEW)
                        .also { analyses = it.second }
                        .first
                }
                Processed(result, entry(agenda, result))
            }
            is DiscoveryOutcome.Found -> {
                val agenda = discovery.fetchAgenda(outcome.meeting.sourceUrl, enrichReports = true)
                val baseline = revisions.baseline(agenda.sourceId)
                meetingId = saveMeeting(
                    agenda,
                    MeetingStatus.IMPORTING,
                    imported = true,
                    publicationStatus = PublicationStatus.CURRENT,
                    commissie = commissie,
                )
                val (result, count) = importAgenda(meetingId, agenda, baseline)
                analyses = count
                Processed(result, entry(agenda, result))
            }
        }
    }

    private fun logResult(result: MeetingCheckResult) {
        val message = "Meeting check finished with {} (source {}, revision {}, error {})"
        if (result.status == MeetingCheckStatus.PARTIAL || result.status == MeetingCheckStatus.FAILED ||
            result.status == MeetingCheckStatus.SOURCE_FAILURE
        ) {
            log.warn(message, result.status, result.meetingSourceId, result.revisionNumber, result.errorCode)
        } else {
            log.info(message, result.status, result.meetingSourceId, result.revisionNumber, result.errorCode)
        }
    }

    private fun importAgenda(
        meetingId: UUID,
        agenda: ParsedMeetingAgenda,
        baseline: RevisionBaseline?,
        publicationStatus: PublicationStatus = PublicationStatus.CURRENT,
        sourceState: SourceState = SourceState.CURRENT,
    ): Pair<MeetingCheckResult, Int> {
        val unreadable = mutableListOf<String>()
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
                    sourceState = sourceState,
                ),
            )
            val summary = documents.ingest(itemId, agenda.agendaDocuments.map { it.toReference() })
            unreadable += summary.unreadableCodes()
            meetingDocuments += summary.documents
        }

        agenda.items.forEach { parsed ->
            val initial = parsed.toAgendaItem(meetingId, ImportStatus.IN_PROGRESS, sourceState)
            val itemId = meetings.upsert(initial)
            val summary = if (parsed.substantive) {
                documents.ingest(itemId, parsed.documents.map { it.toReference() })
            } else {
                null
            }
            val itemComplete = summary?.fullyRead ?: true
            summary?.let { unreadable += it.unreadableCodes() }
            val revisionItem = comparator.currentItem(
                parsed,
                itemId,
                if (parsed.substantive) meetingDocuments + (summary?.documents ?: emptyList()) else emptyList(),
                sourceState,
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

        // A document that cannot be read never holds the import back: it keeps its status and is shown as
        // a warning, and the other documents are analysed. Every check downloads all documents again, so
        // a document that becomes readable later changes the revision and triggers a new analysis.
        if (unreadable.isNotEmpty()) {
            log.warn(
                "{} document(s) of meeting {} could not be read: {}",
                unreadable.size,
                agenda.sourceId,
                unreadable.groupingBy { it }.eachCount(),
            )
        }
        return run {
            val comparison = comparator.compare(agenda, publicationStatus, revisionItems, baseline)
            val stored = revisions.record(
                meetingId,
                agenda,
                publicationStatus,
                revisionItems,
                comparison,
                clock.instant(),
            )
            val analyses = comparison.items.count(ItemDifference::requiresAnalysis)
            (if (comparison.unchanged) {
                if (baseline?.revisionStatus == RevisionStatus.REPROCESSING) {
                    meetings.markAnalysing(meetingId)
                    events.publishEvent(MeetingImportedEvent(meetingId, agenda.sourceId, clock.instant()))
                } else {
                    meetings.markSuccessful(meetingId)
                }
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
            }) to analyses
        }
    }

    private fun nl.vdzon.pvdd.documents.DocumentIngestionSummary.unreadableCodes(): List<String> =
        documents.filter { it.extractionStatus != nl.vdzon.pvdd.documents.ExtractionStatus.EXTRACTED }
            .map { "${it.extractionStatus}${it.errorCode?.let { code -> "/$code" } ?: ""}" }

    private fun saveMeeting(
        agenda: ParsedMeetingAgenda,
        status: MeetingStatus,
        imported: Boolean,
        publicationStatus: PublicationStatus,
        commissie: Commissie,
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
            commissie = commissie,
        ),
    )

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
        const val FAILURE_DESCRIPTION = "Deze vergadering kon niet worden verwerkt. Probeer het later opnieuw."
        private val ANALYSIS_CATEGORIES = setOf(AgendaCategory.A, AgendaCategory.B, AgendaCategory.C)
        private val log = LoggerFactory.getLogger(MeetingCheckWorkflow::class.java)

        private fun safeCode(failure: Exception): String = failure::class.simpleName ?: "UNKNOWN"
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
}
