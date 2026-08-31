package nl.vdzon.pvdd.meetings

import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `same successfully completed meeting stops before documents`() {
        val found = DiscoveryOutcome.Found(DiscoveredMeeting("meeting-future", now.plusSeconds(3600), meetingUrl))
        val discovery = StubDiscovery(found)
        val fixture = fixture(discovery = discovery, lastSuccessful = "meeting-future")

        val result = fixture.workflow.check()
        assertEquals(MeetingCheckStatus.UNCHANGED, result.status)
        assertEquals(0, discovery.fetchCount)
        assertEquals(0, fixture.documents.calls)
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
        val workflow = MeetingCheckWorkflow(
            discovery,
            store,
            documents,
            StubLock(lockAvailable),
            ApplicationEventPublisher { },
            Clock.fixed(now, ZoneOffset.UTC),
        )
        return WorkflowFixture(workflow, store, documents)
    }

    private fun resource(name: String): String = requireNotNull(
        javaClass.getResource("/fixtures/meetings/$name"),
    ).readText()

    private data class WorkflowFixture(
        val workflow: MeetingCheckWorkflow,
        val store: StubStore,
        val documents: CountingDocuments,
    )

    private class StubDiscovery(
        private val outcome: DiscoveryOutcome,
        private val agenda: ParsedMeetingAgenda? = null,
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
}
