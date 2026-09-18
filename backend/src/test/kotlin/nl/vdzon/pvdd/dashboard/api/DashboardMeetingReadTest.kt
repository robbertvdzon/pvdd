package nl.vdzon.pvdd.dashboard.api

import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nl.vdzon.pvdd.analysis.AnalysisFacade
import nl.vdzon.pvdd.auth.ApiAuthenticationFilter
import nl.vdzon.pvdd.auth.AuthConfig
import nl.vdzon.pvdd.auth.Authenticator
import nl.vdzon.pvdd.auth.GoogleIdentity
import nl.vdzon.pvdd.auth.UserSessionService
import nl.vdzon.pvdd.dashboard.AiRunQueryRepository
import nl.vdzon.pvdd.dashboard.DashboardRepository
import nl.vdzon.pvdd.dashboard.MeetingDto
import nl.vdzon.pvdd.dashboard.MeetingOverviewDto
import nl.vdzon.pvdd.dashboard.ProgressDto
import nl.vdzon.pvdd.meetings.MutationGuard
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.http.server.PathContainer
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

/**
 * De leesroute `GET /api/meetings/{id}`: hetzelfde antwoordmodel als `/api/meetings/next`, 404 bij
 * een onbekende id, geen enkele aanraking van de analysekant en dezelfde sessiecontrole als de
 * andere beschermde leesroutes.
 */
class DashboardMeetingReadTest {
    private val analyses = mock(AnalysisFacade::class.java)
    private val dashboard = mock(DashboardRepository::class.java)
    private val controller = DashboardController(
        dashboard,
        analyses,
        MutationGuard(Clock.fixed(Instant.parse("2026-09-17T09:00:00Z"), ZoneOffset.UTC)),
        mock(AiRunQueryRepository::class.java),
    )

    @Test
    fun `a known meeting is returned with its header progress and past marker`() {
        val id = UUID.randomUUID()
        `when`(dashboard.meeting(id)).thenReturn(overview(id, past = true))

        val response = controller.meeting(id)

        assertEquals("COMPLETE", response.status)
        assertEquals(ProgressDto(3, 2, 1), response.progress)
        assertEquals(Instant.parse("2026-09-07T05:00:00Z"), response.lastCheckedAt)
        val meeting = requireNotNull(response.meeting)
        assertEquals("Commissie Ruimte 7 september 2026", meeting.title)
        assertEquals("Commissie Ruimte", meeting.committee)
        assertEquals(Instant.parse("2026-09-07T17:30:00Z"), meeting.startsAt)
        assertEquals("Dreef 3, Haarlem", meeting.location)
        assertEquals(URI("https://noordholland.bestuurlijkeinformatie.nl/Agenda/Index/meeting-past"), meeting.sourceUrl)
        assertEquals("COMPLETE", meeting.status)
        assertEquals(4, meeting.revisionNumber)
        assertEquals("CURRENT", meeting.revisionStatus)
        assertTrue(meeting.past)
    }

    @Test
    fun `a future meeting is not marked as past`() {
        val id = UUID.randomUUID()
        `when`(dashboard.meeting(id)).thenReturn(overview(id, past = false))

        assertFalse(requireNotNull(controller.meeting(id).meeting).past)
    }

    @Test
    fun `an unknown but valid id is not found`() {
        val id = UUID.randomUUID()
        `when`(dashboard.meeting(id)).thenReturn(null)

        assertEquals(HttpStatus.NOT_FOUND, assertFailsWith<ResponseStatusException> { controller.meeting(id) }.statusCode)
    }

    @Test
    fun `reading one meeting never reaches the analysis facade`() {
        val id = UUID.randomUUID()
        `when`(dashboard.meeting(id)).thenReturn(overview(id, past = true))

        controller.meeting(id)

        verifyNoInteractions(analyses)
    }

    // Zonder sessie mag de nieuwe route de applicatie niet bereiken. Staat zij per ongeluk in de
    // uitzonderingenlijst van `shouldNotFilter`, dan gaat het verzoek door de keten heen en faalt
    // deze test; `/api/version` dient als tegenproef dat een uitgezonderde route dat wél doet.
    @Test
    fun `unauthenticated traffic is refused exactly like the existing protected reads`() {
        val filter = ApiAuthenticationFilter(
            Authenticator(AuthConfig("client-id", "production")) { GoogleIdentity("robbertvdzon@gmail.com", true) },
            AuthConfig("client-id", "production"),
            mock(UserSessionService::class.java),
        )
        val meetingId = UUID.randomUUID()
        val reference = statusWithoutSession(filter, "/api/meetings/next")
        assertEquals(HttpStatus.UNAUTHORIZED.value(), reference)

        for (path in listOf("/api/meetings/$meetingId", "/api/meetings/$meetingId/agenda-items")) {
            assertEquals(reference, statusWithoutSession(filter, path), path)
        }

        var exemptRouteReachedTheApplication = false
        filter.doFilter(MockHttpServletRequest("GET", "/api/version"), MockHttpServletResponse()) { _, _ ->
            exemptRouteReachedTheApplication = true
        }
        assertTrue(exemptRouteReachedTheApplication)
    }

    // Spring kiest voor `/api/meetings/next` het letterlijke patroon boven het sjabloon, dus de
    // bestaande route blijft werken naast de nieuwe.
    @Test
    fun `the literal next route keeps priority over the new template route`() {
        val parser = PathPatternParser()
        val patterns = listOf(parser.parse("/api/meetings/{id}"), parser.parse("/api/meetings/next"))
        val requested = PathContainer.parsePath("/api/meetings/next")

        val matching = patterns.filter { it.matches(requested) }.sortedWith(PathPattern.SPECIFICITY_COMPARATOR)

        assertEquals(2, matching.size)
        assertEquals("/api/meetings/next", matching.first().patternString)
    }

    private fun statusWithoutSession(filter: ApiAuthenticationFilter, path: String): Int {
        val response = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("GET", path), response) { _, _ ->
            throw AssertionError("Unauthenticated request reached the application: $path")
        }
        return response.status
    }

    private fun overview(id: UUID, past: Boolean) = MeetingOverviewDto(
        status = "COMPLETE",
        meeting = MeetingDto(
            id = id,
            sourceId = "meeting-past",
            title = "Commissie Ruimte 7 september 2026",
            committee = "Commissie Ruimte",
            startsAt = Instant.parse("2026-09-07T17:30:00Z"),
            endsAt = Instant.parse("2026-09-07T20:30:00Z"),
            location = "Dreef 3, Haarlem",
            sourceUrl = URI("https://noordholland.bestuurlijkeinformatie.nl/Agenda/Index/meeting-past"),
            status = "COMPLETE",
            publicationStatus = "CURRENT",
            revisionNumber = 4,
            canonicalFingerprint = "b".repeat(64),
            revisionStatus = "CURRENT",
            past = past,
        ),
        lastCheckedAt = Instant.parse("2026-09-07T05:00:00Z"),
        progress = ProgressDto(3, 2, 1),
    )
}
