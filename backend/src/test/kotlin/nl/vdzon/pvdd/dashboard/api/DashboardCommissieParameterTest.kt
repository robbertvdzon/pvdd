package nl.vdzon.pvdd.dashboard.api

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import nl.vdzon.pvdd.analysis.AnalysisFacade
import nl.vdzon.pvdd.auth.ApiAuthenticationFilter
import nl.vdzon.pvdd.auth.AuthConfig
import nl.vdzon.pvdd.auth.Authenticator
import nl.vdzon.pvdd.auth.GoogleIdentity
import nl.vdzon.pvdd.auth.UserSessionService
import nl.vdzon.pvdd.dashboard.AdviceVersionsDto
import nl.vdzon.pvdd.dashboard.AiRunQueryRepository
import nl.vdzon.pvdd.dashboard.DashboardRepository
import nl.vdzon.pvdd.dashboard.MeetingDto
import nl.vdzon.pvdd.dashboard.MeetingOverviewDto
import nl.vdzon.pvdd.dashboard.PastMeetingPageDto
import nl.vdzon.pvdd.dashboard.ProgressDto
import nl.vdzon.pvdd.meetings.MutationGuard
import nl.vdzon.pvdd.source.Commissie
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.server.ResponseStatusException
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * De queryparameter `commissie` op de leesroutes: standaard Ruimte, exact `RUIMTE`/`BESTUUR`,
 * alles anders HTTP 400 `invalid_commissie` zonder dat de querylaag wordt aangeroepen.
 */
class DashboardCommissieParameterTest {
    private val dashboard = mock(DashboardRepository::class.java)
    private val controller = DashboardController(
        dashboard,
        mock(AnalysisFacade::class.java),
        MutationGuard(Clock.fixed(Instant.parse("2026-09-17T09:00:00Z"), ZoneOffset.UTC)),
        mock(AiRunQueryRepository::class.java),
    )
    private val id = UUID.randomUUID()

    private val routes: Map<String, (String?) -> Any?> = mapOf(
        "next" to { c -> controller.next(c) },
        "meetings" to { c -> controller.meetings("past", null, null, c) },
        "meeting" to { c -> controller.meeting(id, c) },
        "items" to { c -> controller.items(id, c) },
        "item" to { c -> controller.item(id, c) },
        "adviceVersions" to { c -> controller.adviceVersions(id, c) },
    )

    @Test
    fun `invalid values are refused before any repository access`() {
        for ((name, call) in routes) {
            for (value in listOf("FOO", "", "ruimte", "Bestuur", "bestuur", " BESTUUR", "RUIMTE ", "BESTUUR,RUIMTE")) {
                val failure = assertFailsWith<ResponseStatusException>("$name '$value'") { call(value) }
                assertEquals(HttpStatus.BAD_REQUEST, failure.statusCode, "$name '$value'")
                assertEquals("invalid_commissie", failure.reason, "$name '$value'")
            }
        }
        verifyNoInteractions(dashboard)
    }

    @Test
    fun `a missing parameter reads Ruimte and an explicit value is passed on`() {
        val overview = MeetingOverviewDto("NO_MEETING", null, null, ProgressDto(0, 0, 0))
        `when`(dashboard.overview(Commissie.RUIMTE)).thenReturn(overview)
        `when`(dashboard.overview(Commissie.BESTUUR)).thenReturn(overview)
        `when`(dashboard.meeting(id, Commissie.BESTUUR)).thenReturn(overview)
        `when`(dashboard.pastMeetings("past", null, null, Commissie.BESTUUR)).thenReturn(PastMeetingPageDto(emptyList(), null, 0))
        `when`(dashboard.agendaItems(id, Commissie.RUIMTE)).thenReturn(emptyList())
        `when`(dashboard.adviceVersions(id, Commissie.BESTUUR)).thenReturn(AdviceVersionsDto(emptyList()))

        controller.next(null)
        controller.next("BESTUUR")
        controller.meeting(id, "BESTUUR")
        controller.meetings("past", null, null, "BESTUUR")
        controller.items(id, null)
        controller.adviceVersions(id, "BESTUUR")

        verify(dashboard).overview(Commissie.RUIMTE)
        verify(dashboard).overview(Commissie.BESTUUR)
        verify(dashboard).meeting(id, Commissie.BESTUUR)
        verify(dashboard).pastMeetings("past", null, null, Commissie.BESTUUR)
        verify(dashboard).agendaItems(id, Commissie.RUIMTE)
        verify(dashboard).adviceVersions(id, Commissie.BESTUUR)
    }

    @Test
    fun `an id of the other commissie is a plain 404`() {
        `when`(dashboard.meeting(id, Commissie.BESTUUR)).thenReturn(null)
        val failure = assertFailsWith<ResponseStatusException> { controller.meeting(id, "BESTUUR") }
        assertEquals(HttpStatus.NOT_FOUND, failure.statusCode)
        assertEquals(null, failure.reason)
    }

    @Test
    fun `the answer for Ruimte only adds the commissie fields`() {
        val meeting = MeetingDto(
            id = id, sourceId = "s", title = "t", committee = "Commissie Ruimte",
            startsAt = Instant.parse("2026-09-07T17:30:00Z"), endsAt = null, location = null,
            sourceUrl = java.net.URI("https://example.test/s"), status = "COMPLETE", publicationStatus = "CURRENT",
            revisionNumber = 1, canonicalFingerprint = null, revisionStatus = null, past = false,
        )
        val json = jacksonObjectMapper().readTree(jacksonObjectMapper().writeValueAsString(meeting))
        val legacy = setOf(
            "id", "sourceId", "title", "committee", "startsAt", "endsAt", "location", "sourceUrl", "status",
            "publicationStatus", "revisionNumber", "canonicalFingerprint", "revisionStatus", "past",
        )
        assertEquals(legacy + setOf("commissie", "commissieNaam"), json.propertyNames().toSet())
        assertEquals("RUIMTE", json["commissie"].asString())
        assertEquals("Ruimte", json["commissieNaam"].asString())
    }

    @Test
    fun `unauthenticated traffic gets 401 with and without the parameter`() {
        val filter = ApiAuthenticationFilter(
            Authenticator(AuthConfig("client-id", "production")) { GoogleIdentity("robbertvdzon@gmail.com", true) },
            AuthConfig("client-id", "production"),
            mock(UserSessionService::class.java),
        )
        for (path in listOf("/api/meetings/next", "/api/meetings", "/api/meetings/$id", "/api/meetings/$id/agenda-items")) {
            for (query in listOf(null, "commissie=BESTUUR", "commissie=FOO")) {
                val request = MockHttpServletRequest("GET", path).apply {
                    queryString = query
                    query?.let { setParameter("commissie", it.substringAfter('=')) }
                }
                val response = MockHttpServletResponse()
                filter.doFilter(request, response) { _, _ -> throw AssertionError("reached the application: $path $query") }
                assertEquals(401, response.status, "$path $query")
            }
        }
    }
}
