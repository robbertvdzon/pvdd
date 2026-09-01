package nl.vdzon.pvdd.analysis

import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nl.vdzon.pvdd.policy.PolicyPositionDto
import nl.vdzon.pvdd.policy.PolicyReferenceDto
import nl.vdzon.pvdd.policy.PolicySnapshotDto
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class PolicyPositionCatalogueTest {
    private val mapper = jacksonObjectMapper()
    private val snapshot = PolicySnapshotDto(
        id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        version = 3,
        fingerprint = "fingerprint",
        activatedAt = Instant.parse("2026-09-01T19:54:02Z"),
    )

    @Test
    fun `catalogue contains every compact position and its references`() {
        val positions = listOf(
            position("Dierenwelzijn", "Bescherm dieren bij provinciale besluiten", "DIEREN"),
            position("Bereikbaarheid", "Geef fiets en openbaar vervoer voorrang", "MOBILITEIT"),
        )

        val source = requireNotNull(policyPositionCatalogueSource(snapshot, positions, mapper))
        val payload = mapper.readTree(source.text)

        assertEquals(CitationSourceType.POLICY_POSITIONS, source.sourceType)
        assertEquals("Volledig actueel standpuntenoverzicht (2)", source.section)
        assertEquals(3, payload.path("snapshotVersion").intValue())
        assertEquals(2, payload.path("positions").size())
        val serializedPositions = payload.path("positions").iterator().asSequence().toList()
        assertEquals(positions.map { it.title }, serializedPositions.map { it.path("title").stringValue() })
        assertTrue(serializedPositions.all { it.path("references").size() == 1 })
    }

    @Test
    fun `empty snapshot does not add a catalogue source`() {
        assertNull(policyPositionCatalogueSource(snapshot, emptyList(), mapper))
    }

    private fun position(title: String, summary: String, theme: String) = PolicyPositionDto(
        id = UUID.randomUUID(),
        title = title,
        summary = summary,
        themes = listOf(theme),
        direction = "STEUNEN",
        status = "ACTUEEL",
        sourceDate = LocalDate.of(2026, 8, 31),
        lastChangedAt = Instant.parse("2026-09-01T19:54:02Z"),
        references = listOf(
            PolicyReferenceDto(
                url = URI("https://noordholland.partijvoordedieren.nl/onze-idealen/$theme"),
                sourceType = "IDEAL",
                title = title,
                pageNumber = null,
                section = "Kern",
            ),
        ),
    )
}
