package nl.vdzon.pvdd.meetings

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import nl.vdzon.pvdd.source.Commissie
import org.junit.jupiter.api.BeforeEach

/**
 * De synthetische Bestuur-reeks van de acceptatiemock, gelezen door de echte discovery en parser (stub-HTTP:
 * de mock draait lokaal, geen database, geen credentials). Ruimte blijft ongewijzigd.
 */
class BestuurMockContractTest {
    private lateinit var mock: BestuurMockServer

    @BeforeEach
    fun start() {
        mock = BestuurMockServer()
    }

    @AfterTest
    fun stop() = mock.close()

    private fun bestuur() = mock.discovery().discoverCommissie(Commissie.BESTUUR, NOW).single()
    private fun ruimte() = mock.discovery().discoverCommissie(Commissie.RUIMTE, NOW).single()

    private fun published(): ParsedMeetingAgenda {
        val found = assertIs<DiscoveryOutcome.Found>(bestuur())
        return mock.discovery().fetchAgenda(found.meeting.sourceUrl, enrichReports = true)
    }

    @Test
    fun `the default is the Bestuur meeting of 30 November 2026 13 to 17 Amsterdam time without an agenda`() {
        val outcome = assertIs<DiscoveryOutcome.AgendaUnpublished>(bestuur())
        assertEquals("bestuur-meeting-20261130", outcome.meeting.sourceId)
        assertEquals(Instant.parse("2026-11-30T12:00:00Z"), outcome.meeting.startsAt)
        val agenda = mock.discovery().fetchAgenda(outcome.meeting.sourceUrl, enrichReports = true)
        assertEquals(Instant.parse("2026-11-30T16:00:00Z"), agenda.endsAt)
        assertEquals("Commissie Bestuur", agenda.committee)
        assertFalse(agenda.published)
        assertTrue(agenda.items.none { it.substantive })
    }

    @Test
    fun `Ruimte is untouched by the Bestuur series and by every Bestuur scenario`() {
        val before = assertIs<DiscoveryOutcome.Found>(ruimte())
        listOf("bestuur-gepubliceerd", "bestuur-gewijzigd-stuk", "bestuur-bronfout", "bestuur-niet-gepubliceerd").forEach {
            mock.select(it)
            assertEquals(before, ruimte(), it)
        }
        assertEquals("acceptance-meeting-v1", before.meeting.sourceId)
    }

    @Test
    fun `the published scenario has A B and C items, one item without document and items with readable documents`() {
        mock.select("bestuur-gepubliceerd")
        val agenda = published()

        assertTrue(agenda.published)
        val substantive = agenda.items.filter { it.substantive }
        assertEquals(setOf(AgendaCategory.A, AgendaCategory.B, AgendaCategory.C), substantive.map { it.category }.toSet())
        assertEquals(listOf("bestuur-item-a2"), substantive.filter { it.documents.isEmpty() }.map { it.sourceId })
        val withDocuments = substantive.flatMap { it.documents }
        assertEquals(setOf("bestuur-doc-a1", "bestuur-doc-b1", "bestuur-doc-c1"), withDocuments.map { it.sourceId }.toSet())
        withDocuments.forEach { assertTrue(mock.documentText(it.sourceId).contains("verzonnen"), it.sourceId) }
        assertTrue(substantive.single { it.category == AgendaCategory.C }.documents.isNotEmpty(), "het C-punt krijgt zijn stuk via de rapportpagina")
    }

    @Test
    fun `the changed document scenario changes only the content of the asked document`() {
        mock.select("bestuur-gewijzigd-stuk")
        val before = published()
        val a1 = mock.documentText("bestuur-doc-a1")
        val b1 = mock.documentText("bestuur-doc-b1")

        mock.changeDocument("bestuur-doc-a1")

        assertNotEquals(a1, mock.documentText("bestuur-doc-a1"))
        assertEquals(b1, mock.documentText("bestuur-doc-b1"))
        assertEquals(before.items.map { it.sourceHash }, published().items.map { it.sourceHash })
    }

    @Test
    fun `the source failure scenario fails Bestuur alone`() {
        mock.select("bestuur-bronfout")
        assertIs<DiscoveryOutcome.SourceFailure>(bestuur())
        assertIs<DiscoveryOutcome.Found>(ruimte())
    }

    @Test
    fun `an unknown scenario is rejected as before and keeps the current Bestuur scenario`() {
        mock.select("bestuur-gepubliceerd")
        assertEquals(400, mock.request("POST", "/api/scenarios/bestuur-bestaat-niet").first)
        assertIs<DiscoveryOutcome.Found>(bestuur())
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-29T10:00:00Z")
    }
}
