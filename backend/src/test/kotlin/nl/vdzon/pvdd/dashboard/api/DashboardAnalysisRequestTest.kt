package nl.vdzon.pvdd.dashboard.api

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import nl.vdzon.pvdd.analysis.AnalysisCommandResult
import nl.vdzon.pvdd.analysis.AnalysisCommandStatus
import nl.vdzon.pvdd.analysis.AnalysisFacade
import nl.vdzon.pvdd.dashboard.AiRunQueryRepository
import nl.vdzon.pvdd.dashboard.DashboardRepository
import nl.vdzon.pvdd.meetings.MutationGuard
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.server.ResponseStatusException

class DashboardAnalysisRequestTest {
    private val analyses = mock(AnalysisFacade::class.java)
    private val dashboard = mock(DashboardRepository::class.java)
    private val controller = DashboardController(
        dashboard,
        analyses,
        MutationGuard(Clock.fixed(Instant.parse("2026-09-17T09:00:00Z"), ZoneOffset.UTC)),
        mock(AiRunQueryRepository::class.java),
    )

    @Test
    fun `a meeting in the past is refused with conflict and meeting_in_past`() {
        val id = answerWith(AnalysisCommandStatus.MEETING_IN_PAST)

        val failure = assertFailsWith<ResponseStatusException> { request(id) }

        assertEquals(HttpStatus.CONFLICT, failure.statusCode)
        assertEquals("meeting_in_past", failure.reason)
    }

    @Test
    fun `an unknown meeting keeps returning not found`() {
        val id = answerWith(AnalysisCommandStatus.NOT_FOUND)

        assertEquals(HttpStatus.NOT_FOUND, assertFailsWith<ResponseStatusException> { request(id) }.statusCode)
    }

    @Test
    fun `a queued meeting is accepted unchanged`() {
        val id = answerWith(AnalysisCommandStatus.QUEUED)

        assertEquals(AnalysisCommandStatus.QUEUED, request(id).status)
        assertEquals(
            HttpStatus.ACCEPTED,
            DashboardController::class.java
                .getDeclaredMethod("requestAnalysis", UUID::class.java, String::class.java, String::class.java)
                .getAnnotation(ResponseStatus::class.java)
                .value,
        )
    }

    @Test
    fun `reading an agenda never reaches the analysis facade`() {
        val meetingId = UUID.randomUUID()
        val itemId = UUID.randomUUID()
        `when`(dashboard.agendaItems(meetingId)).thenReturn(emptyList())
        `when`(dashboard.item(itemId)).thenReturn(null)

        assertEquals(emptyList(), controller.items(meetingId))
        assertFailsWith<ResponseStatusException> { controller.item(itemId) }

        verifyNoInteractions(analyses)
    }

    private fun answerWith(status: AnalysisCommandStatus): UUID {
        val id = UUID.randomUUID()
        `when`(analyses.requestMeeting(id)).thenReturn(AnalysisCommandResult(status, id))
        return id
    }

    private fun request(id: UUID) = controller.requestAnalysis(id, "robbertvdzon@gmail.com", "analyse-key-$id")
}
