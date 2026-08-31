package nl.vdzon.pvdd.meetings

import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class AgendaParserTest {
    private val parser = AgendaParser()
    private val meetingUrl = URI("http://localhost:18091/Agenda/Index/meeting-future")

    @Test
    fun `parses A B and C agenda with hierarchy and attachments`() {
        val agenda = parser.parse(fixture("agenda-full.html"), meetingUrl)

        assertEquals("meeting-future", agenda.sourceId)
        assertEquals("Commissie Ruimte", agenda.committee)
        assertEquals("Statenzaal", agenda.location)
        assertTrue(agenda.published)
        assertEquals(8, agenda.items.size)

        val housing = agenda.items.single { it.sourceId == "item-a-housing" }
        assertEquals(AgendaCategory.A, housing.category)
        assertEquals("section-a", housing.parentSourceId)
        assertEquals("doc-housing", housing.documents.single().sourceId)
        assertNotNull(housing.explanation)
        assertNotNull(housing.treatmentProposal)

        val road = agenda.items.single { it.sourceId == "item-b-road" }
        assertEquals(AgendaCategory.B, road.category)
        assertEquals(2, road.documents.size)

        val pause = agenda.items.single { it.sourceId == "item-empty" }
        assertFalse(pause.substantive)

        val reports = agenda.items.filter { it.category == AgendaCategory.C && it.substantive }
        assertEquals(listOf("report-nature", "report-air"), reports.map { it.sourceId })
        assertTrue(reports.all { it.parentSourceId == "section-c" })
    }

    @Test
    fun `recognises an unpublished agenda`() {
        val agenda = parser.parse(fixture("agenda-unpublished.html"), meetingUrl)
        assertFalse(agenda.published)
        assertTrue(agenda.items.isEmpty())
    }

    @Test
    fun `enriches a C agenda report detail`() {
        val item = parser.parseReportItem(
            fixture("report-item.html"),
            URI("http://localhost:18091/Reports/Item/report-nature"),
            sequence = 7,
            parentSourceId = "section-c",
        )
        assertEquals("report-nature", item.sourceId)
        assertEquals(AgendaCategory.C, item.category)
        assertEquals("doc-nature", item.documents.single().sourceId)
    }

    @Test
    fun `unknown source structure fails closed`() {
        val failure = assertFailsWith<AgendaParseException> {
            parser.parse(fixture("unknown-structure.html"), meetingUrl)
        }
        assertEquals(SourceErrorCode.UNKNOWN_HTML, failure.code)
    }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.getResource("/fixtures/meetings/$name"),
    ).readText()
}
