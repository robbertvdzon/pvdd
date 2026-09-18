package nl.vdzon.pvdd.dashboard.api

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import nl.vdzon.pvdd.analysis.AnalysisFacade
import nl.vdzon.pvdd.auth.ApiAuthenticationFilter
import nl.vdzon.pvdd.auth.AuthConfig
import nl.vdzon.pvdd.auth.Authenticator
import nl.vdzon.pvdd.auth.GoogleIdentity
import nl.vdzon.pvdd.auth.UserSessionService
import nl.vdzon.pvdd.dashboard.AiRunQueryRepository
import nl.vdzon.pvdd.dashboard.DashboardRepository
import nl.vdzon.pvdd.dashboard.PastMeetingDto
import nl.vdzon.pvdd.dashboard.PastMeetingPageDto
import nl.vdzon.pvdd.meetings.MutationGuard
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.server.ResponseStatusException
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * De leeslijst `GET /api/meetings?state=past`: parametervalidatie vóór elke databasetoegang, de
 * vertaling naar HTTP 400 met foutcode `invalid_meeting_query`, de standaardlimiet en de
 * begrenzing van de queries. Het rijniveaugedrag (sortering, cursor, tellingen) ligt vast in
 * `DatabaseIntegrationTest`.
 */
class DashboardPastMeetingsTest {
    private val analyses = mock(AnalysisFacade::class.java)
    private val dashboard = mock(DashboardRepository::class.java)
    private val controller = DashboardController(
        dashboard,
        analyses,
        MutationGuard(Clock.fixed(Instant.parse("2026-09-18T09:00:00Z"), ZoneOffset.UTC)),
        mock(AiRunQueryRepository::class.java),
    )

    // Precies de gevallen uit het acceptatiecriterium: ontbrekende of afwijkende state, een limit
    // buiten 1..50 of niet-numeriek, en een onleesbare cursor.
    private val invalidQueries = listOf(
        Triple(null, null, null),
        Triple("future", null, null),
        Triple("", null, null),
        Triple("past", "0", null),
        Triple("past", "51", null),
        Triple("past", "abc", null),
        Triple("past", null, "!!!"),
    )

    @Test
    fun `every invalid query is refused before the database is touched`() {
        for ((state, limit, cursor) in invalidQueries) {
            val jdbc = RecordingJdbcTemplate()
            val repository = DashboardRepository(jdbc, jacksonObjectMapper())

            assertFailsWith<IllegalArgumentException>("$state/$limit/$cursor") {
                repository.pastMeetings(state, limit, cursor)
            }
            assertTrue(jdbc.statements.isEmpty(), "$state/$limit/$cursor raakte de database: ${jdbc.statements}")
        }
    }

    @Test
    fun `an invalid query becomes HTTP 400 with the meeting error code`() {
        for ((state, limit, cursor) in invalidQueries) {
            `when`(dashboard.pastMeetings(state, limit, cursor)).thenThrow(IllegalArgumentException("invalid"))

            val refusal = assertFailsWith<ResponseStatusException> { controller.meetings(state, limit, cursor) }

            assertEquals(HttpStatus.BAD_REQUEST, refusal.statusCode, "$state/$limit/$cursor")
            assertEquals("invalid_meeting_query", refusal.reason, "$state/$limit/$cursor")
        }
        verifyNoInteractions(analyses)
    }

    @Test
    fun `a valid page is returned unchanged and never reaches the analysis facade`() {
        val page = PastMeetingPageDto(
            items = listOf(
                PastMeetingDto(
                    UUID.fromString("6c9ad377-5837-41b7-9f68-573ccf58c859"),
                    "Commissie Ruimte 7 september 2026",
                    Instant.parse("2026-09-07T17:30:00Z"),
                    "Dreef 3, Haarlem",
                    substantiveItemCount = 6,
                    completedAdviceCount = 5,
                ),
            ),
            nextCursor = "MjAyNi0wOS0wN1QxNzozMDowMFo",
            total = 34,
        )
        `when`(dashboard.pastMeetings("past", null, null)).thenReturn(page)

        assertEquals(page, controller.meetings("past", null, null))
        verifyNoInteractions(analyses)
    }

    // Zonder `limit` haalt de query twintig rijen op, plus de ene extra rij die alleen als
    // cursorsignaal dient. Tegelijk legt deze test vast dat elke nieuwe query begrensd is.
    @Test
    fun `the default page size is twenty and every query is bounded`() {
        val jdbc = RecordingJdbcTemplate()

        val page = DashboardRepository(jdbc, jacksonObjectMapper()).pastMeetings("past", null, null)

        assertEquals(PastMeetingPageDto(emptyList(), null, 0), page)
        assertEquals(2, jdbc.statements.size)
        assertTrue(jdbc.statements.all { it.first.contains("LIMIT") }, jdbc.statements.joinToString())
        assertEquals(listOf<Any?>(21), jdbc.statements.first().second)
    }

    @Test
    fun `an explicit limit at the edges of the allowed range is accepted`() {
        for ((limit, expected) in mapOf("1" to 2, "50" to 51)) {
            val jdbc = RecordingJdbcTemplate()

            DashboardRepository(jdbc, jacksonObjectMapper()).pastMeetings("past", limit, null)

            assertEquals(listOf<Any?>(expected), jdbc.statements.first().second, limit)
        }
    }

    // De nieuwe route staat niet in de uitzonderingenlijst van `shouldNotFilter` en krijgt zonder
    // sessie dus exact hetzelfde weigerantwoord als de bestaande agenda-leesroute.
    @Test
    fun `unauthenticated traffic is refused exactly like the existing agenda read`() {
        val filter = ApiAuthenticationFilter(
            Authenticator(AuthConfig("client-id", "production")) { GoogleIdentity("robbertvdzon@gmail.com", true) },
            AuthConfig("client-id", "production"),
            mock(UserSessionService::class.java),
        )
        val reference = statusWithoutSession(filter, "/api/meetings/next")
        assertEquals(HttpStatus.UNAUTHORIZED.value(), reference)

        val response = MockHttpServletResponse()
        val request = MockHttpServletRequest("GET", "/api/meetings").apply { queryString = "state=past" }
        filter.doFilter(request, response) { _, _ ->
            throw AssertionError("Unauthenticated request reached the application: /api/meetings")
        }

        assertEquals(reference, response.status)
        assertEquals("{\"error\":\"authentication_failed\"}", response.contentAsString)
    }

    private fun statusWithoutSession(filter: ApiAuthenticationFilter, path: String): Int {
        val response = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("GET", path), response) { _, _ ->
            throw AssertionError("Unauthenticated request reached the application: $path")
        }
        return response.status
    }

    /** Legt elk uitgevoerd statement vast zonder database, zodat zichtbaar is wat de query doet. */
    private class RecordingJdbcTemplate : JdbcTemplate() {
        val statements = mutableListOf<Pair<String, List<Any?>>>()

        override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> {
            statements += sql to args.toList()
            return emptyList()
        }

        override fun <T : Any> queryForObject(sql: String, requiredType: Class<T>): T? {
            statements += sql to emptyList<Any?>()
            return null
        }
    }
}
