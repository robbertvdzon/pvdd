package nl.vdzon.pvdd

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import nl.vdzon.pvdd.analysis.AnalysisStatus
import nl.vdzon.pvdd.documents.ExtractionStatus
import nl.vdzon.pvdd.meetings.AgendaCategory
import nl.vdzon.pvdd.meetings.ImportStatus
import nl.vdzon.pvdd.meetings.MeetingStatus
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class FunctionalContractsTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `AI result schema only requires processable Markdown`() {
        val schema = jsonResource("/schemas/content-result-v2.json")
        val required: Set<String> = schema.path("required").iterator().asSequence().map { it.asText() }.toSet()
        assertEquals(setOf("displayTitle", "shortConclusion", "content"), required)
        assertEquals(setOf("displayTitle", "shortConclusion", "content"), schema.path("properties").propertyNames().toSet())
    }

    @Test
    fun `functional state contracts are explicit and closed`() {
        assertTrue(MeetingStatus.entries.containsAll(listOf(MeetingStatus.AGENDA_UNPUBLISHED, MeetingStatus.PARTIAL)))
        assertEquals(setOf("A", "B", "C", "OTHER"), AgendaCategory.entries.map { it.name }.toSet())
        assertTrue(ImportStatus.entries.containsAll(listOf(ImportStatus.IN_PROGRESS, ImportStatus.COMPLETE, ImportStatus.FAILED)))
        assertTrue(ExtractionStatus.entries.containsAll(listOf(ExtractionStatus.EXTRACTED, ExtractionStatus.OCR_REQUIRED)))
        assertTrue(AnalysisStatus.entries.containsAll(listOf(AnalysisStatus.WAITING_FOR_WORKER, AnalysisStatus.CANCELLED)))
    }

    @Test
    fun `synthetic fixtures contain all agenda shapes without copied personal data`() {
        val full = textResource("/fixtures/meetings/agenda-full.html")
        assertTrue(full.contains("A-agenda"))
        assertTrue(full.contains("B-agenda"))
        assertTrue(full.contains("C-agenda"))
        assertTrue(full.contains("/Agenda/Document/"))
        assertTrue(full.contains("/Reports/Item/"))
        assertFalse(full.contains("@"))
        assertFalse(full.contains("Marianne Poot"))
        assertFalse(full.contains("6c9ad377-5837-41b7-9f68-573ccf58c859"))
    }

    private fun jsonResource(path: String) = mapper.readTree(textResource(path))

    private fun textResource(path: String): String {
        val stream = javaClass.getResourceAsStream(path)
        assertNotNull(stream, "Missing test resource $path")
        return stream.bufferedReader().use { it.readText() }
    }
}
