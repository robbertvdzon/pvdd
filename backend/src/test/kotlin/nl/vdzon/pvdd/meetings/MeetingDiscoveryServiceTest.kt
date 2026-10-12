package nl.vdzon.pvdd.meetings

import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import nl.vdzon.pvdd.source.MeetingSourceGateway
import nl.vdzon.pvdd.source.MeetingSourceProperties
import nl.vdzon.pvdd.source.SourcePage
import nl.vdzon.pvdd.source.SourceTransportCode
import nl.vdzon.pvdd.source.SourceTransportException
import org.junit.jupiter.api.Test

class MeetingDiscoveryServiceTest {
    private val baseUrl = URI("http://localhost:18091")
    private val now = Instant.parse("2026-08-31T10:00:00Z")
    private val properties = MeetingSourceProperties(baseUrl = baseUrl)

    @Test
    fun `finds first future meeting from current and next year`() {
        val service = service { uri ->
            val body = when {
                uri.path.contains("RetrieveAgendasForYear") -> fixture("year-2026.html")
                uri.path.endsWith("meeting-future") -> fixture("agenda-full.html")
                else -> error("Unexpected URI $uri")
            }
            SourcePage(uri, 200, "text/html", body)
        }

        val outcome = assertIs<DiscoveryOutcome.Found>(service.discover().first())
        assertEquals("meeting-future", outcome.meeting.sourceId)
        assertEquals(Instant.parse("2026-09-14T16:30:00Z"), outcome.meeting.startsAt)
    }

    @Test
    fun `keeps looking ahead when the last known meeting took place earlier today`() {
        val service = service { uri ->
            val body = when {
                uri.path.contains("RetrieveAgendasForYear") -> fixture("year-2026-meeting-today.html")
                uri.path.endsWith("meeting-today") -> fixture("agenda-full.html")
                    .replace("maandag 14 september 2026", "maandag 31 augustus 2026")
                    .replace("18:30 - 22:30", "08:00 - 09:30")
                uri.path.endsWith("meeting-future") -> fixture("agenda-full.html")
                else -> error("Unexpected URI $uri")
            }
            SourcePage(uri, 200, "text/html", body)
        }

        // De vergadering van vandaag begon om 08:00 (06:00Z) en is dus al geweest; de dagfilter laat
        // haar door, maar de controle op `startsAt <= nu` slaat haar over en de discovery kijkt verder.
        val outcome = assertIs<DiscoveryOutcome.Found>(service.discover().first())
        assertEquals("meeting-future", outcome.meeting.sourceId)
        assertEquals(Instant.parse("2026-09-14T16:30:00Z"), outcome.meeting.startsAt)
    }

    @Test
    fun `stops safely when there is no future meeting`() {
        val service = service { uri -> SourcePage(uri, 200, "text/html", fixture("no-future-meeting.html")) }
        assertIs<DiscoveryOutcome.NoFutureMeeting>(service.discover().first())
    }

    @Test
    fun `distinguishes an unpublished agenda`() {
        val service = service { uri ->
            val body = if (uri.path.contains("RetrieveAgendasForYear")) fixture("year-2026.html") else fixture("agenda-unpublished.html")
            SourcePage(uri, 200, "text/html", body)
        }
        assertIs<DiscoveryOutcome.AgendaUnpublished>(service.discover().first())
    }

    @Test
    fun `maps source failures to a safe result`() {
        val service = service { throw SourceTransportException(SourceTransportCode.READ_TIMEOUT) }
        val outcome = assertIs<DiscoveryOutcome.SourceFailure>(service.discover().first())
        assertEquals(SourceErrorCode.READ_TIMEOUT, outcome.code)
    }

    @Test
    fun `loads C report details without live network`() {
        val service = service { uri ->
            val body = when {
                uri.path.endsWith("report-nature") -> fixture("report-item.html")
                uri.path.endsWith("report-air") -> fixture("report-item.html")
                    .replace("report-nature", "report-air")
                    .replace("herstel van een natuurverbinding", "luchtkwaliteit")
                else -> fixture("agenda-full.html")
            }
            SourcePage(uri, 200, "text/html", body)
        }
        val agenda = service.fetchAgenda(baseUrl.resolve("/Agenda/Index/meeting-future"))
        val nature = agenda.items.single { it.sourceId == "report-nature" }
        assertEquals("doc-nature", nature.documents.single().sourceId)
    }

    @Test
    fun `keeps visible preview item when report enrichment times out`() {
        val service = service { uri ->
            if (uri.path.endsWith("report-nature") || uri.path.endsWith("report-air")) {
                throw SourceTransportException(SourceTransportCode.READ_TIMEOUT)
            }
            SourcePage(uri, 200, "text/html", fixture("agenda-full.html"))
        }

        val agenda = service.fetchAgenda(baseUrl.resolve("/Agenda/Index/meeting-future"))
        val previewItems = agenda.items.filter { it.substantive && it.category == AgendaCategory.C }

        assertTrue(previewItems.isNotEmpty())
        assertTrue(previewItems.all { it.documents.isEmpty() })
    }

    // --- Geordende lijst: eerstvolgende plus maximaal twee binnen tien kalenderdagen ---

    @Test
    fun `AC1 returns the next meeting and a second one inside the window in order`() {
        val source = SyntheticSource(meeting("m-19", amsterdam(2026, 10, 19, 19, 0)), meeting("m-22", amsterdam(2026, 10, 22, 19, 0)))
        val outcomes = source.service().discover(amsterdam(2026, 10, 12, 10, 0))
        assertEquals(listOf("m-19", "m-22"), outcomes.map { assertIs<DiscoveryOutcome.Found>(it).meeting.sourceId })
    }

    @Test
    fun `AC2 includes day ten across the end of daylight saving time`() {
        val inside = SyntheticSource(meeting("m-19", amsterdam(2026, 10, 19, 19, 0)), meeting("m-29", amsterdam(2026, 10, 29, 19, 0)))
        val outcomes = inside.service().discover(amsterdam(2026, 10, 12, 10, 0))
        assertEquals(2, outcomes.size)
        assertEquals("m-29", assertIs<DiscoveryOutcome.Found>(outcomes[1]).meeting.sourceId)
    }

    @Test
    fun `AC2 excludes day eleven`() {
        val source = SyntheticSource(meeting("m-19", amsterdam(2026, 10, 19, 19, 0)), meeting("m-30", amsterdam(2026, 10, 30, 19, 0)))
        val outcomes = source.service().discover(amsterdam(2026, 10, 12, 10, 0))
        assertEquals(listOf("m-19"), outcomes.map { assertIs<DiscoveryOutcome.Found>(it).meeting.sourceId })
        assertEquals(0, source.fetches["m-30"] ?: 0)
    }

    @Test
    fun `AC3 determines the calendar day in Amsterdam time instead of UTC`() {
        val lastNight = amsterdam(2026, 10, 29, 23, 30)
        val firstMorning = amsterdam(2026, 10, 30, 0, 30)
        assertEquals(java.time.LocalDate.of(2026, 10, 29), java.time.LocalDate.ofInstant(firstMorning, ZoneOffset.UTC))
        val source = SyntheticSource(
            meeting("m-19", amsterdam(2026, 10, 19, 19, 0)),
            meeting("m-late", lastNight),
            meeting("m-early", firstMorning),
        )
        val outcomes = source.service().discover(amsterdam(2026, 10, 12, 10, 0))
        assertEquals(listOf("m-19", "m-late"), outcomes.map { assertIs<DiscoveryOutcome.Found>(it).meeting.sourceId })
    }

    @Test
    fun `AC4 never returns more than three and never fetches the fourth candidate`() {
        val source = SyntheticSource(
            meeting("m-1", amsterdam(2026, 10, 19, 19, 0)),
            meeting("m-2", amsterdam(2026, 10, 20, 19, 0)),
            meeting("m-3", amsterdam(2026, 10, 21, 19, 0)),
            meeting("m-4", amsterdam(2026, 10, 22, 19, 0)),
            meeting("m-5", amsterdam(2026, 10, 23, 19, 0)),
        )
        val outcomes = source.service().discover(amsterdam(2026, 10, 12, 10, 0))
        assertEquals(listOf("m-1", "m-2", "m-3"), outcomes.map { assertIs<DiscoveryOutcome.Found>(it).meeting.sourceId })
        assertEquals(0, source.fetches["m-4"] ?: 0)
        assertEquals(0, source.fetches["m-5"] ?: 0)
        assertTrue(source.fetches.values.sum() <= 3)
    }

    @Test
    fun `AC5 skips past meetings and a meeting exactly at now`() {
        val now = amsterdam(2026, 10, 12, 10, 0)
        val source = SyntheticSource(
            meeting("past-1", amsterdam(2026, 10, 1, 19, 0)),
            meeting("past-2", amsterdam(2026, 10, 5, 19, 0)),
            meeting("m-1", amsterdam(2026, 10, 14, 19, 0)),
            meeting("m-2", amsterdam(2026, 10, 16, 19, 0)),
            meeting("at-now", now),
        )
        val outcomes = source.service().discover(now)
        assertEquals(listOf("m-1", "m-2"), outcomes.map { assertIs<DiscoveryOutcome.Found>(it).meeting.sourceId })
    }

    @Test
    fun `AC5 keeps source order for identical start times and is repeatable`() {
        val moment = amsterdam(2026, 10, 19, 19, 0)
        val source = SyntheticSource(meeting("m-b", moment), meeting("m-a", moment))
        val service = source.service()
        val now = amsterdam(2026, 10, 12, 10, 0)
        val ids = { service.discover(now).map { assertIs<DiscoveryOutcome.Found>(it).meeting.sourceId } }
        assertEquals(listOf("m-b", "m-a"), ids())
        assertEquals(ids(), ids())
    }

    @Test
    fun `AC6 reports an extra meeting with an unpublished agenda without importing anything`() {
        // discover heeft geen schrijfpoort; "niets opgeslagen" is hier bewezen doordat er per kandidaat
        // precies één agenda-ophaalactie is en geen rapportverrijking (enrichReports = false).
        val source = SyntheticSource(
            meeting("m-1", amsterdam(2026, 10, 19, 19, 0)),
            meeting("m-2", amsterdam(2026, 10, 21, 19, 0), AgendaState.UNPUBLISHED),
            meeting("m-3", amsterdam(2026, 10, 22, 19, 0), AgendaState.PREVIEW_WITH_ITEMS),
        )
        val outcomes = source.service().discover(amsterdam(2026, 10, 12, 10, 0))
        assertIs<DiscoveryOutcome.Found>(outcomes[0])
        assertEquals("m-2", assertIs<DiscoveryOutcome.AgendaUnpublished>(outcomes[1]).meeting.sourceId)
        assertEquals("m-3", assertIs<DiscoveryOutcome.AgendaUnpublished>(outcomes[2]).meeting.sourceId)
        assertEquals(mapOf("m-1" to 1, "m-2" to 1, "m-3" to 1), source.fetches)
        assertTrue(source.reportFetches.isEmpty())
    }

    @Test
    fun `AC6 still reports a preview agenda of the next meeting as unpublished`() {
        val source = SyntheticSource(meeting("m-1", amsterdam(2026, 10, 19, 19, 0), AgendaState.PREVIEW_WITH_ITEMS))
        val outcomes = source.service().discover(amsterdam(2026, 10, 12, 10, 0))
        assertEquals("m-1", assertIs<DiscoveryOutcome.AgendaUnpublished>(outcomes.single()).meeting.sourceId)
    }

    @Test
    fun `AC7 isolates an agenda failure to the affected extra meeting`() {
        val source = SyntheticSource(
            meeting("m-1", amsterdam(2026, 10, 19, 19, 0)),
            meeting("m-2", amsterdam(2026, 10, 20, 19, 0), failure = SourceTransportCode.READ_TIMEOUT),
            meeting("m-3", amsterdam(2026, 10, 21, 19, 0)),
        )
        val outcomes = source.service().discover(amsterdam(2026, 10, 12, 10, 0))
        assertEquals(3, outcomes.size)
        assertEquals("m-1", assertIs<DiscoveryOutcome.Found>(outcomes[0]).meeting.sourceId)
        assertEquals(SourceErrorCode.READ_TIMEOUT, assertIs<DiscoveryOutcome.SourceFailure>(outcomes[1]).code)
        assertEquals("m-3", assertIs<DiscoveryOutcome.Found>(outcomes[2]).meeting.sourceId)
    }

    @Test
    fun `AC7 turns a year page failure into one source failure for the whole check`() {
        val source = SyntheticSource(meeting("m-1", amsterdam(2026, 10, 19, 19, 0)), yearPageFailure = SourceTransportCode.HTTP_ERROR)
        val outcomes = source.service().discover(amsterdam(2026, 10, 12, 10, 0))
        assertEquals(SourceErrorCode.HTTP_ERROR, assertIs<DiscoveryOutcome.SourceFailure>(outcomes.single()).code)
    }

    @Test
    fun `AC8 returns a single NoFutureMeeting for only past meetings or an empty source`() {
        val now = amsterdam(2026, 10, 12, 10, 0)
        val onlyPast = SyntheticSource(meeting("past", amsterdam(2026, 10, 1, 19, 0)))
        assertIs<DiscoveryOutcome.NoFutureMeeting>(onlyPast.service().discover(now).single())
        assertIs<DiscoveryOutcome.NoFutureMeeting>(SyntheticSource().service().discover(now).single())
    }

    @Test
    fun `AC8 returns one element for a single meeting inside the window`() {
        val source = SyntheticSource(meeting("m-1", amsterdam(2026, 10, 19, 19, 0)), meeting("far", amsterdam(2026, 11, 20, 19, 0)))
        val outcomes = source.service().discover(amsterdam(2026, 10, 12, 10, 0))
        assertEquals(listOf("m-1"), outcomes.map { assertIs<DiscoveryOutcome.Found>(it).meeting.sourceId })
    }

    // --- Testvoorzieningen: synthetische bron met registratie van ophaalacties ---

    private enum class AgendaState { PUBLISHED, UNPUBLISHED, PREVIEW_WITH_ITEMS }

    private data class SyntheticMeeting(
        val id: String,
        val startsAt: Instant,
        val state: AgendaState,
        val failure: SourceTransportCode?,
    )

    private fun meeting(id: String, startsAt: Instant, state: AgendaState = AgendaState.PUBLISHED, failure: SourceTransportCode? = null) =
        SyntheticMeeting(id, startsAt, state, failure)

    /** Zet een Amsterdamse datum/tijd om naar een `Instant`. */
    private fun amsterdam(year: Int, month: Int, day: Int, hour: Int, minute: Int): Instant =
        LocalDateTime.of(year, month, day, hour, minute).atZone(ZoneId.of("Europe/Amsterdam")).toInstant()

    private inner class SyntheticSource(
        vararg meetings: SyntheticMeeting,
        private val yearPageFailure: SourceTransportCode? = null,
    ) {
        private val sourceMeetings = meetings.toList()
        val fetches = linkedMapOf<String, Int>()
        val reportFetches = mutableListOf<URI>()
        private val zone = ZoneId.of("Europe/Amsterdam")
        private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.forLanguageTag("nl-NL"))
        private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

        fun service() = service { uri -> SourcePage(uri, 200, "text/html", serve(uri)) }

        private fun serve(uri: URI): String {
            if (uri.path.contains("RetrieveAgendasForYear")) {
                yearPageFailure?.let { throw SourceTransportException(it) }
                val year = uri.query.substringAfter("year=").toInt()
                return sourceMeetings.filter { it.startsAt.atZone(zone).year == year }.joinToString("\n") { m ->
                    val local = m.startsAt.atZone(zone)
                    """<li><a href="/Agenda/Index/${m.id}"><div>${dayFormat.format(local)} <span class="sr-only">${local.year}</span></div></a></li>"""
                }
            }
            val id = uri.path.substringAfterLast('/')
            val meeting = sourceMeetings.firstOrNull { it.id == id } ?: run { reportFetches += uri; error("Unexpected URI $uri") }
            fetches.merge(id, 1, Int::plus)
            meeting.failure?.let { throw SourceTransportException(it) }
            val local = meeting.startsAt.atZone(zone)
            val base = when (meeting.state) {
                AgendaState.PUBLISHED -> fixture("agenda-full.html")
                AgendaState.UNPUBLISHED -> fixture("agenda-unpublished.html")
                AgendaState.PREVIEW_WITH_ITEMS -> fixture("agenda-prepublished-with-c.html")
            }
            return base
                .replace("maandag 14 september 2026", "${dayFormat.format(local)} ${local.year}")
                .replace("14 september 2026", "${dayFormat.format(local).substringAfter(' ')} ${local.year}")
                .replace("18:30 - 22:30", "${timeFormat.format(local)} - ${timeFormat.format(local.plusHours(2))}")
                .replace("meeting-future", id)
        }
    }

    private fun service(fetch: (URI) -> SourcePage) = MeetingDiscoveryService(
        source = MeetingSourceGateway(fetch),
        properties = properties,
        parser = AgendaParser(),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun fixture(name: String): String = requireNotNull(
        javaClass.getResource("/fixtures/meetings/$name"),
    ).readText()
}
