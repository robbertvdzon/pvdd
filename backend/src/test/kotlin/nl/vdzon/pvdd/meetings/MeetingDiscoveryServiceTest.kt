package nl.vdzon.pvdd.meetings

import java.net.URI
import java.time.Clock
import java.time.Instant
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

        val outcome = assertIs<DiscoveryOutcome.Found>(service.discover())
        assertEquals("meeting-future", outcome.meeting.sourceId)
        assertEquals(Instant.parse("2026-09-14T16:30:00Z"), outcome.meeting.startsAt)
    }

    @Test
    fun `stops safely when there is no future meeting`() {
        val service = service { uri -> SourcePage(uri, 200, "text/html", fixture("no-future-meeting.html")) }
        assertIs<DiscoveryOutcome.NoFutureMeeting>(service.discover())
    }

    @Test
    fun `distinguishes an unpublished agenda`() {
        val service = service { uri ->
            val body = if (uri.path.contains("RetrieveAgendasForYear")) fixture("year-2026.html") else fixture("agenda-unpublished.html")
            SourcePage(uri, 200, "text/html", body)
        }
        assertIs<DiscoveryOutcome.AgendaUnpublished>(service.discover())
    }

    @Test
    fun `maps source failures to a safe result`() {
        val service = service { throw SourceTransportException(SourceTransportCode.READ_TIMEOUT) }
        val outcome = assertIs<DiscoveryOutcome.SourceFailure>(service.discover())
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
