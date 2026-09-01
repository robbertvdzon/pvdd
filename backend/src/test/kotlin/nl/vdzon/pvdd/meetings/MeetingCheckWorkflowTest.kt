package nl.vdzon.pvdd.meetings

import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nl.vdzon.pvdd.documents.DocumentIngestionSummary
import nl.vdzon.pvdd.documents.DocumentIngestor
import nl.vdzon.pvdd.documents.DocumentReference
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

class MeetingCheckWorkflowTest {
    private val now = Instant.parse("2026-08-31T10:00:00Z")
    private val meetingUrl = URI("https://noordholland.bestuurlijkeinformatie.nl/Agenda/Index/meeting-future")

    @Test
    fun `no future meeting performs no import or document download`() {
        val discovery = StubDiscovery(DiscoveryOutcome.NoFutureMeeting)
        val fixture = fixture(discovery = discovery)
        val result = fixture.workflow.check()
        assertEquals(MeetingCheckStatus.NO_FUTURE_MEETING, result.status)
        assertEquals(0, discovery.fetchCount)
        assertEquals(0, fixture.documents.calls)
    }

    @Test
    fun `same meeting is refetched and unchanged source does not start a new analysis`() {
        val found = DiscoveryOutcome.Found(DiscoveredMeeting("meeting-future", now.plusSeconds(3600), meetingUrl))
        val discovery = StubDiscovery(found, AgendaParser().parse(resource("agenda-full.html"), meetingUrl))
        val fixture = fixture(discovery = discovery)

        assertEquals(MeetingCheckStatus.IMPORTED, fixture.workflow.check().status)
        fixture.revisions.markCurrent()
        val result = fixture.workflow.check()
        assertEquals(MeetingCheckStatus.UNCHANGED, result.status)
        assertEquals(2, discovery.fetchCount)
        assertEquals(1, result.revisionNumber)
        assertEquals(1, fixture.store.successful.size)
        assertEquals(1, fixture.events.size)
    }

    @Test
    fun `unchanged reprocessing revision resumes analysis reconciliation`() {
        val found = DiscoveryOutcome.Found(DiscoveredMeeting("meeting-future", now.plusSeconds(3600), meetingUrl))
        val discovery = StubDiscovery(found, AgendaParser().parse(resource("agenda-full.html"), meetingUrl))
        val fixture = fixture(discovery = discovery)

        assertEquals(MeetingCheckStatus.IMPORTED, fixture.workflow.check().status)
        assertEquals(MeetingCheckStatus.UNCHANGED, fixture.workflow.check().status)

        assertEquals(2, fixture.events.size)
        assertEquals(0, fixture.store.successful.size)
    }

    @Test
    fun `unpublished agenda is stored but never marked successful`() {
        val discovered = DiscoveredMeeting("meeting-future", now.plusSeconds(3600), meetingUrl)
        val discovery = StubDiscovery(
            DiscoveryOutcome.AgendaUnpublished(discovered),
            AgendaParser().parse(resource("agenda-unpublished.html"), meetingUrl),
        )
        val fixture = fixture(discovery = discovery)

        val result = fixture.workflow.check()
        assertEquals(MeetingCheckStatus.AGENDA_UNPUBLISHED, result.status)
        assertEquals(1, discovery.fetchCount)
        assertEquals(0, fixture.store.successful.size)
        assertEquals(MeetingStatus.AGENDA_UNPUBLISHED, fixture.store.savedMeetings.single().status)
        assertEquals(0, fixture.documents.calls)
    }

    @Test
    fun `published source change creates a new revision and starts reanalysis`() {
        val parsed = AgendaParser().parse(resource("agenda-full.html"), meetingUrl)
        val discovery = StubDiscovery(
            DiscoveryOutcome.Found(DiscoveredMeeting("meeting-future", now.plusSeconds(3600), meetingUrl)),
            parsed,
        )
        val fixture = fixture(discovery = discovery)
        assertEquals(MeetingCheckStatus.IMPORTED, fixture.workflow.check().status)

        discovery.agenda = parsed.copy(
            items = parsed.items.map { item ->
                if (item.sourceId == parsed.items.first { it.substantive }.sourceId) {
                    item.copy(treatmentProposal = "Gewijzigd behandelvoorstel")
                } else item
            },
        )
        val changed = fixture.workflow.check()

        assertEquals(MeetingCheckStatus.IMPORTED, changed.status)
        assertEquals(2, changed.revisionNumber)
        assertTrue(DifferenceType.METADATA_CHANGED in changed.differences)
        assertEquals(2, fixture.events.size)
    }

    @Test
    fun `durable lock rejects a second active check`() {
        val discovery = StubDiscovery(DiscoveryOutcome.NoFutureMeeting)
        val fixture = fixture(discovery = discovery, lockAvailable = false)

        val result = fixture.workflow.check()
        assertEquals(MeetingCheckStatus.ALREADY_RUNNING, result.status)
        assertFalse(discovery.discoverCalled)
        assertEquals(0, fixture.documents.calls)
    }

    private fun fixture(
        discovery: StubDiscovery,
        lastSuccessful: String? = null,
        lockAvailable: Boolean = true,
    ): WorkflowFixture {
        val store = StubStore(lastSuccessful)
        val documents = CountingDocuments()
        val events = mutableListOf<MeetingImportedEvent>()
        val revisions = InMemoryRevisionStore()
        val workflow = MeetingCheckWorkflow(
            discovery,
            store,
            documents,
            StubLock(lockAvailable),
            ApplicationEventPublisher { event -> if (event is MeetingImportedEvent) events += event },
            Clock.fixed(now, ZoneOffset.UTC),
            AgendaRevisionComparator(),
            revisions,
        )
        return WorkflowFixture(workflow, store, documents, events, revisions)
    }

    private fun resource(name: String): String = requireNotNull(
        javaClass.getResource("/fixtures/meetings/$name"),
    ).readText()

    private data class WorkflowFixture(
        val workflow: MeetingCheckWorkflow,
        val store: StubStore,
        val documents: CountingDocuments,
        val events: List<MeetingImportedEvent>,
        val revisions: InMemoryRevisionStore,
    )

    private class StubDiscovery(
        private val outcome: DiscoveryOutcome,
        var agenda: ParsedMeetingAgenda? = null,
    ) : MeetingDiscoveryGateway {
        var fetchCount = 0
        var discoverCalled = false

        override fun discover(now: Instant): DiscoveryOutcome {
            discoverCalled = true
            return outcome
        }

        override fun fetchAgenda(sourceUrl: URI, enrichReports: Boolean): ParsedMeetingAgenda {
            fetchCount++
            return requireNotNull(agenda)
        }
    }

    private class StubStore(private val lastSuccessful: String?) : MeetingStore {
        val savedMeetings = mutableListOf<Meeting>()
        val successful = mutableListOf<UUID>()

        override fun upsert(meeting: Meeting): UUID {
            savedMeetings += meeting
            return meeting.id
        }

        override fun upsert(item: AgendaItem): UUID = item.id
        override fun lastSuccessfulSourceId(): String? = lastSuccessful
        override fun markSuccessful(meetingId: UUID) { successful += meetingId }
        override fun markFailed(meetingId: UUID, errorCode: String) = Unit
        override fun markAnalysing(meetingId: UUID) = Unit
        override fun markPartial(meetingId: UUID, errorCode: String) = Unit
    }

    private class CountingDocuments : DocumentIngestor {
        var calls = 0
        override fun ingest(agendaItemId: UUID, references: List<DocumentReference>): DocumentIngestionSummary {
            calls++
            return DocumentIngestionSummary(emptyList(), true)
        }
    }

    private class StubLock(private val available: Boolean) : WorkflowLock {
        override fun tryAcquire(lockName: String, ownerId: UUID): Boolean = available
        override fun release(lockName: String, ownerId: UUID) = Unit
    }

    private class InMemoryRevisionStore : SourceRevisionStore {
        private var current: RevisionBaseline? = null

        override fun baseline(meetingSourceId: String): RevisionBaseline? = current

        fun markCurrent() {
            current = current?.copy(revisionStatus = RevisionStatus.CURRENT)
        }

        override fun record(
            meetingId: UUID,
            agenda: ParsedMeetingAgenda,
            publicationStatus: PublicationStatus,
            items: List<RevisionItem>,
            comparison: RevisionComparison,
            checkedAt: Instant,
        ): StoredRevision {
            val existing = current
            if (existing != null && existing.publicationStatus == publicationStatus &&
                existing.canonicalFingerprint == comparison.canonicalFingerprint
            ) return StoredRevision(existing.revisionId, existing.revisionNumber, comparison)

            val revision = RevisionBaseline(
                UUID.randomUUID(),
                (existing?.revisionNumber ?: 0) + 1,
                publicationStatus,
                comparison.canonicalFingerprint,
                items.associateBy(RevisionItem::sourceId),
                if (comparison.requiresAnalysis) RevisionStatus.REPROCESSING else RevisionStatus.CURRENT,
            )
            current = revision
            return StoredRevision(revision.revisionId, revision.revisionNumber, comparison)
        }
    }
}
