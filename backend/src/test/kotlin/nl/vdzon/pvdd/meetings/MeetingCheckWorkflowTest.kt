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
import nl.vdzon.pvdd.meetings.api.MeetingCheckController
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
    fun `available items on an unpublished agenda are imported and analysed immediately`() {
        val discovered = DiscoveredMeeting("meeting-future", now.plusSeconds(3600), meetingUrl)
        val previewAgenda = AgendaParser().parse(resource("agenda-full.html"), meetingUrl).copy(published = false)
        val discovery = StubDiscovery(DiscoveryOutcome.AgendaUnpublished(discovered), previewAgenda)
        val fixture = fixture(discovery = discovery)

        val preview = fixture.workflow.check()

        assertEquals(MeetingCheckStatus.IMPORTED, preview.status)
        assertEquals(setOf(DifferenceType.ITEM_ADDED), preview.differences)
        assertTrue(fixture.documents.calls > 0)
        assertEquals(1, fixture.events.size)
        assertEquals(PublicationStatus.PREVIEW, fixture.store.savedMeetings.single().publicationStatus)
        assertEquals(MeetingStatus.IMPORTING, fixture.store.savedMeetings.single().status)
    }

    @Test
    fun `publication after a processed preview starts a new analysis version`() {
        val discovered = DiscoveredMeeting("meeting-future", now.plusSeconds(3600), meetingUrl)
        val agenda = AgendaParser().parse(resource("agenda-full.html"), meetingUrl)
        val discovery = StubDiscovery(DiscoveryOutcome.AgendaUnpublished(discovered), agenda.copy(published = false))
        val fixture = fixture(discovery = discovery)

        assertEquals(MeetingCheckStatus.IMPORTED, fixture.workflow.check().status)
        fixture.revisions.markCurrent()
        discovery.outcome = DiscoveryOutcome.Found(discovered)
        discovery.agenda = agenda.copy(published = true)

        val published = fixture.workflow.check()

        assertEquals(MeetingCheckStatus.IMPORTED, published.status)
        assertTrue(DifferenceType.PUBLICATION_STATUS in published.differences)
        assertEquals(2, fixture.events.size)
    }

    @Test
    fun `new information on a processed preview starts reanalysis before publication`() {
        val discovered = DiscoveredMeeting("meeting-future", now.plusSeconds(3600), meetingUrl)
        val agenda = AgendaParser().parse(resource("agenda-full.html"), meetingUrl)
        val discovery = StubDiscovery(DiscoveryOutcome.AgendaUnpublished(discovered), agenda.copy(published = false))
        val fixture = fixture(discovery = discovery)

        assertEquals(MeetingCheckStatus.IMPORTED, fixture.workflow.check().status)
        fixture.revisions.markCurrent()
        discovery.agenda = agenda.copy(
            published = false,
            items = agenda.items.map { item ->
                if (item.substantive) item.copy(explanation = "Nieuwe informatie in de voorlopige agenda") else item
            },
        )

        val updatedPreview = fixture.workflow.check()

        assertEquals(MeetingCheckStatus.IMPORTED, updatedPreview.status)
        assertTrue(DifferenceType.METADATA_CHANGED in updatedPreview.differences)
        assertEquals(2, fixture.events.size)
        assertEquals(PublicationStatus.PREVIEW, fixture.store.savedMeetings.last().publicationStatus)
    }

    @Test
    fun `preview analysis proceeds with available content when a document is temporarily unavailable`() {
        val discovered = DiscoveredMeeting("meeting-future", now.plusSeconds(3600), meetingUrl)
        val agenda = AgendaParser().parse(resource("agenda-full.html"), meetingUrl).copy(published = false)
        val fixture = fixture(
            discovery = StubDiscovery(DiscoveryOutcome.AgendaUnpublished(discovered), agenda),
            documentsFullyRead = false,
        )

        val result = fixture.workflow.check()

        assertEquals(MeetingCheckStatus.IMPORTED, result.status)
        assertEquals(1, fixture.events.size)
        assertTrue(fixture.documents.calls > 0)
    }

    @Test
    fun `published agenda is imported and analysed when a document cannot be read`() {
        val parsed = AgendaParser().parse(resource("agenda-full.html"), meetingUrl)
        val fixture = fixture(
            discovery = StubDiscovery(
                DiscoveryOutcome.Found(DiscoveredMeeting("meeting-future", now.plusSeconds(3600), meetingUrl)),
                parsed,
            ),
            documentsFullyRead = false,
        )

        val result = fixture.workflow.check()

        assertEquals(MeetingCheckStatus.IMPORTED, result.status)
        assertEquals(1, fixture.events.size)
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

    @Test
    fun `every returned meeting is processed and reported under one lock`() {
        val fx = multiFixture(listOf("a", "b"))
        val result = fx.workflow.check()

        assertEquals(1, fx.lock.acquires)
        assertEquals(MeetingCheckStatus.IMPORTED, result.status)
        assertEquals("a", result.meetingSourceId)
        assertEquals(listOf("a", "b"), result.meetings.map { it.meetingSourceId })
        assertTrue(result.meetings.all { it.outcome == MeetingCheckOutcome.NEW_DOCUMENTS && it.newAnalyses > 0 })
        assertEquals(listOf("a", "b"), fx.events.map { it.meetingSourceId })
        assertEquals(2, fx.store.savedMeetings.size)
    }

    @Test
    fun `repeated check without changes reports nothing changed for every meeting`() {
        val fx = multiFixture(listOf("a", "b"))
        fx.workflow.check()
        fx.revisions.markCurrent()
        val result = fx.workflow.check()

        assertTrue(result.meetings.all { it.outcome == MeetingCheckOutcome.UNCHANGED && it.newAnalyses == 0 })
        assertEquals(2, fx.events.size)
    }

    @Test
    fun `failure of one meeting does not stop the next and carries no technical detail`() {
        val fx = multiFixture(listOf("a", "b", "c"), failing = setOf("b"))
        val result = fx.workflow.check()

        assertEquals(listOf("a", "b", "c"), result.meetings.map { it.meetingSourceId })
        assertEquals(
            listOf(MeetingCheckOutcome.NEW_DOCUMENTS, MeetingCheckOutcome.FAILED, MeetingCheckOutcome.NEW_DOCUMENTS),
            result.meetings.map { it.outcome },
        )
        val failed = result.meetings[1]
        assertEquals(0, failed.newAnalyses)
        assertEquals(MeetingCheckWorkflow.FAILURE_DESCRIPTION, failed.errorDescription)
        assertFalse(failed.errorDescription!!.contains("Exception"))
        assertEquals(listOf("a", "c"), fx.events.map { it.meetingSourceId })
    }

    @Test
    fun `extra meeting with unpublished agenda stores nothing and starts no analysis`() {
        val fx = multiFixture(listOf("a", "b"), unpublished = setOf("b"))
        val result = fx.workflow.check()

        assertEquals(MeetingCheckOutcome.AGENDA_UNPUBLISHED, result.meetings[1].outcome)
        assertEquals(null, result.meetings[1].meetingId)
        assertEquals(0, result.meetings[1].newAnalyses)
        assertEquals(listOf("a"), fx.store.savedMeetings.map { it.sourceId })
        assertEquals(listOf("a"), fx.events.map { it.meetingSourceId })
        assertEquals(1, fx.discovery.fetched.size)
    }

    @Test
    fun `a single source failure keeps the whole check failing without a list`() {
        val discovery = MultiDiscovery(
            listOf(DiscoveryOutcome.SourceFailure(SourceErrorCode.HTTP_ERROR)),
            emptyMap(),
        )
        val result = fixture(discovery = discovery).workflow.check()
        assertEquals(MeetingCheckStatus.SOURCE_FAILURE, result.status)
        assertTrue(result.meetings.isEmpty())
    }

    @Test
    fun `a source failure among several meetings only fails that meeting`() {
        val agenda = AgendaParser().parse(resource("agenda-full.html"), meetingUrl)
        val found = DiscoveryOutcome.Found(DiscoveredMeeting("a", now.plusSeconds(3600), meetingUrl))
        val discovery = MultiDiscovery(
            listOf(found, DiscoveryOutcome.SourceFailure(SourceErrorCode.HTTP_ERROR)),
            mapOf(meetingUrl to agenda),
        )
        val result = fixture(discovery = discovery).workflow.check()
        assertEquals(MeetingCheckStatus.IMPORTED, result.status)
        assertEquals(MeetingCheckOutcome.FAILED, result.meetings[1].outcome)
    }

    @Test
    fun `workflow never touches a meeting outside the returned list`() {
        val fx = multiFixture(listOf("a", "b", "c"))
        fx.workflow.check()
        assertEquals(setOf("a", "b", "c"), fx.discovery.fetched.map { it.path.substringAfterLast('/') }.toSet())
        assertEquals(3, fx.store.savedMeetings.size)
    }

    @Test
    fun `check-now answers 409 when locked and 200 with the list otherwise`() {
        val guard = MutationGuard(Clock.fixed(now, ZoneOffset.UTC))
        val locked = fixture(discovery = StubDiscovery(DiscoveryOutcome.NoFutureMeeting), lockAvailable = false)
        val busy = MeetingCheckController(locked.workflow, guard).checkNow("a@example.org", "check-key-0000000001")
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, busy.statusCode)

        val fx = multiFixture(listOf("a", "b"), failing = setOf("b"))
        val ok = MeetingCheckController(fx.workflow, guard).checkNow("a@example.org", "check-key-0000000002")
        assertEquals(org.springframework.http.HttpStatus.OK, ok.statusCode)
        assertEquals("a", ok.body!!.meetingSourceId)
        assertEquals(2, ok.body!!.meetings.size)

        assertEquals(MeetingCheckStatus.IMPORTED, ok.body!!.status)
        assertEquals(MeetingCheckOutcome.FAILED, ok.body!!.meetings[1].outcome)
    }

    @Test
    fun `scheduler and controller call the same workflow bean`() {
        val fx = multiFixture(listOf("a"))
        MeetingCheckScheduler(fx.workflow).scheduledCheck()
        assertEquals("0 0 5 * * *", MeetingCheckScheduler.CRON)
        assertEquals("Europe/Amsterdam", MeetingCheckScheduler.ZONE)
        assertEquals(1, fx.lock.acquires)
    }

    private class MultiFixture(
        val workflow: MeetingCheckWorkflow,
        val store: StubStore,
        val discovery: MultiDiscovery,
        val events: List<MeetingImportedEvent>,
        val revisions: InMemoryRevisionStore,
        val lock: StubLock,
    )

    private fun multiFixture(
        ids: List<String>,
        failing: Set<String> = emptySet(),
        unpublished: Set<String> = emptySet(),
    ): MultiFixture {
        val template = AgendaParser().parse(resource("agenda-full.html"), meetingUrl)
        val urls = ids.associateWith { URI("https://noordholland.bestuurlijkeinformatie.nl/Agenda/Index/$it") }
        val outcomes = ids.map {
            val meeting = DiscoveredMeeting(it, now.plusSeconds(3600), urls.getValue(it))
            if (it in unpublished) DiscoveryOutcome.AgendaUnpublished(meeting) else DiscoveryOutcome.Found(meeting)
        }
        val discovery = MultiDiscovery(
            outcomes,
            urls.entries.associate { (id, url) -> url to template.copy(sourceId = id, sourceUrl = url) },
            failing.map { urls.getValue(it) }.toSet(),
        )
        val store = StubStore(null)
        val events = mutableListOf<MeetingImportedEvent>()
        val revisions = InMemoryRevisionStore()
        val lock = StubLock(true)
        val workflow = MeetingCheckWorkflow(
            discovery,
            store,
            CountingDocuments(true),
            lock,
            ApplicationEventPublisher { event -> if (event is MeetingImportedEvent) events += event },
            Clock.fixed(now, ZoneOffset.UTC),
            AgendaRevisionComparator(),
            revisions,
        )
        return MultiFixture(workflow, store, discovery, events, revisions, lock)
    }

    private class MultiDiscovery(
        private val outcomes: List<DiscoveryOutcome>,
        private val agendas: Map<URI, ParsedMeetingAgenda>,
        private val failing: Set<URI> = emptySet(),
    ) : MeetingDiscoveryGateway {
        val fetched = mutableListOf<URI>()

        override fun discover(now: Instant): List<DiscoveryOutcome> = outcomes

        override fun fetchAgenda(sourceUrl: URI, enrichReports: Boolean): ParsedMeetingAgenda {
            fetched += sourceUrl
            check(sourceUrl !in failing) { "boom at https://internal/secret" }
            return agendas.getValue(sourceUrl)
        }
    }

    private fun fixture(
        discovery: MeetingDiscoveryGateway,
        lastSuccessful: String? = null,
        lockAvailable: Boolean = true,
        documentsFullyRead: Boolean = true,
    ): WorkflowFixture {
        val store = StubStore(lastSuccessful)
        val documents = CountingDocuments(documentsFullyRead)
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
        var outcome: DiscoveryOutcome,
        var agenda: ParsedMeetingAgenda? = null,
    ) : MeetingDiscoveryGateway {
        var fetchCount = 0
        var discoverCalled = false

        override fun discover(now: Instant): List<DiscoveryOutcome> {
            discoverCalled = true
            return listOf(outcome)
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

    private class CountingDocuments(private val fullyRead: Boolean) : DocumentIngestor {
        var calls = 0
        override fun ingest(agendaItemId: UUID, references: List<DocumentReference>): DocumentIngestionSummary {
            calls++
            return DocumentIngestionSummary(emptyList(), fullyRead)
        }
    }

    private class StubLock(private val available: Boolean) : WorkflowLock {
        var acquires = 0

        override fun tryAcquire(lockName: String, ownerId: UUID): Boolean {
            acquires++
            return available
        }
        override fun release(lockName: String, ownerId: UUID) = Unit
    }

    private class InMemoryRevisionStore : SourceRevisionStore {
        private val baselines = mutableMapOf<String, RevisionBaseline>()

        override fun baseline(meetingSourceId: String): RevisionBaseline? = baselines[meetingSourceId]

        fun markCurrent() {
            baselines.replaceAll { _, baseline -> baseline.copy(revisionStatus = RevisionStatus.CURRENT) }
        }

        override fun record(
            meetingId: UUID,
            agenda: ParsedMeetingAgenda,
            publicationStatus: PublicationStatus,
            items: List<RevisionItem>,
            comparison: RevisionComparison,
            checkedAt: Instant,
        ): StoredRevision {
            val existing = baselines[agenda.sourceId]
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
            baselines[agenda.sourceId] = revision
            return StoredRevision(revision.revisionId, revision.revisionNumber, comparison)
        }
    }
}
