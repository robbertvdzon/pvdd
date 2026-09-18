package nl.vdzon.pvdd.analysis

import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import nl.vdzon.pvdd.meetings.Meeting
import nl.vdzon.pvdd.meetings.MeetingRepository
import nl.vdzon.pvdd.meetings.MeetingStatus
import nl.vdzon.pvdd.runtime.AgentRuntimeGateway
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class AnalysisRequestTest {
    private val now = Instant.parse("2026-09-17T09:00:00Z")
    private val repository = mock(AnalysisRepository::class.java)
    private val meetings = mock(MeetingRepository::class.java)
    private val facade = AnalysisFacade(
        repository,
        meetings,
        mock(AgentRuntimeGateway::class.java),
        Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `a meeting that already started is refused without queueing work`() {
        val meetingId = knownMeeting(now.minusSeconds(1))

        val result = facade.requestMeeting(meetingId)

        assertEquals(AnalysisCommandStatus.MEETING_IN_PAST, result.status)
        assertEquals(meetingId, result.id)
        verify(repository, never()).queueMeeting(meetingId)
    }

    @Test
    fun `a meeting starting exactly now is refused`() {
        val meetingId = knownMeeting(now)

        assertEquals(AnalysisCommandStatus.MEETING_IN_PAST, facade.requestMeeting(meetingId).status)
        verify(repository, never()).queueMeeting(meetingId)
    }

    @Test
    fun `a meeting that still has to start is queued unchanged`() {
        val meetingId = knownMeeting(now.plusSeconds(1))

        val result = facade.requestMeeting(meetingId)

        assertEquals(AnalysisCommandStatus.QUEUED, result.status)
        assertEquals(meetingId, result.id)
        verify(repository).queueMeeting(meetingId)
    }

    @Test
    fun `an unknown meeting is not found before any date check`() {
        val meetingId = UUID.randomUUID()
        `when`(meetings.findMeeting(meetingId)).thenReturn(null)

        val result = facade.requestMeeting(meetingId)

        assertEquals(AnalysisCommandStatus.NOT_FOUND, result.status)
        assertNull(result.id)
        verify(repository, never()).queueMeeting(meetingId)
    }

    private fun knownMeeting(startsAt: Instant): UUID {
        val meetingId = UUID.randomUUID()
        `when`(meetings.findMeeting(meetingId)).thenReturn(
            Meeting(
                id = meetingId,
                sourceId = "meeting-$meetingId",
                committee = "Commissie Ruimte",
                startsAt = startsAt,
                endsAt = startsAt.plusSeconds(3600),
                location = "Statenzaal",
                title = "Synthetische vergadering",
                sourceUrl = URI("https://noordholland.bestuurlijkeinformatie.nl/Agenda/Index/meeting-$meetingId"),
                sourceHash = "a".repeat(64),
                status = MeetingStatus.COMPLETE,
                checkedAt = startsAt.minusSeconds(3600),
                importedAt = startsAt.minusSeconds(3600),
            ),
        )
        return meetingId
    }
}
