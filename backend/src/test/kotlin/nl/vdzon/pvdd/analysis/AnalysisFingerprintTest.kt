package nl.vdzon.pvdd.analysis

import java.net.URI
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import nl.vdzon.pvdd.meetings.AgendaCategory
import nl.vdzon.pvdd.meetings.AgendaItem
import nl.vdzon.pvdd.meetings.ImportStatus
import org.junit.jupiter.api.Test

class AnalysisFingerprintTest {
    @Test
    fun `category transition changes fingerprint and source ordering does not`() {
        val item = AgendaItem(
            UUID.randomUUID(), UUID.randomUUID(), "item-1", null, 1, "1",
            AgendaCategory.B, "Titel", "Toelichting", "Bespreken",
            URI("https://example.test/item-1"), "source-hash", true, ImportStatus.COMPLETE,
        )
        val agenda = source("agenda-item-1", "Agenda")
        val policy = source("policy-p1-c1", "Beleid", CitationSourceType.POLICY_PROGRAMME)

        assertEquals(analysisFingerprint(item, listOf(agenda, policy)), analysisFingerprint(item, listOf(policy, agenda)))
        assertNotEquals(analysisFingerprint(item, listOf(agenda, policy)), analysisFingerprint(item.copy(category = AgendaCategory.C), listOf(agenda, policy)))
    }

    private fun source(
        id: String,
        text: String,
        type: CitationSourceType = CitationSourceType.MEETING_DOCUMENT,
    ) = AnalysisSource(id, type, URI("https://example.test/$id"), 1, "Sectie", text)
}
