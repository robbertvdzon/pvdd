package nl.vdzon.pvdd.meetings

import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class AgendaRevisionComparatorTest {
    private val comparator = AgendaRevisionComparator()
    private val agenda = AgendaParser().parse(resource("agenda-full.html"), MEETING_URL)

    @Test
    fun `available preview items require analysis and retain their provisional state`() {
        val preview = agenda.items
            .filter { it.substantive && it.category == AgendaCategory.C }
            .map { comparator.currentItem(it, UUID.randomUUID(), emptyList(), SourceState.PREVIEW) }
        val result = comparator.compare(agenda.copy(published = false), PublicationStatus.PREVIEW, preview, null)

        assertTrue(preview.isNotEmpty())
        assertTrue(preview.all { it.category == AgendaCategory.C && it.documents.isEmpty() && it.sourceState == SourceState.PREVIEW })
        assertTrue(result.requiresAnalysis)
        assertEquals(setOf(DifferenceType.ITEM_ADDED), result.differences)
    }

    @Test
    fun `canonical fingerprint ignores formatting and tracking query`() {
        val items = listOf(item("item-a", sequence = 1))
        val formatted = agenda.copy(
            committee = "  Commissie   Ruimte ",
            sourceUrl = URI("https://noordholland.bestuurlijkeinformatie.nl/Agenda/Index/meeting-future?utm_source=test"),
        )
        assertEquals(
            comparator.meetingFingerprint(agenda, PublicationStatus.CURRENT, items),
            comparator.meetingFingerprint(formatted, PublicationStatus.CURRENT, items),
        )
    }

    @Test
    fun `classifies item and document differences and limits reanalysis`() {
        val originalA = item("item-a", sequence = 1, category = AgendaCategory.A, hash = "a")
        val originalB = item("item-b", sequence = 2, category = AgendaCategory.B, hash = "b")
        val baselineFingerprint = comparator.meetingFingerprint(agenda, PublicationStatus.CURRENT, listOf(originalA, originalB))
        val baseline = RevisionBaseline(
            UUID.randomUUID(),
            1,
            PublicationStatus.CURRENT,
            baselineFingerprint,
            listOf(originalA, originalB).associateBy(RevisionItem::sourceId),
        )
        val changedA = item("item-a", sequence = 2, category = AgendaCategory.C, hash = "c")
            .copy(title = "Gewijzigd voorstel")
        val added = item("item-new", sequence = 3, category = AgendaCategory.B, hash = "d")

        val result = comparator.compare(agenda, PublicationStatus.CURRENT, listOf(changedA, added), baseline)
        val aChanges = result.items.single { it.item?.sourceId == "item-a" }.differences

        assertTrue(DifferenceType.ITEM_MOVED in aChanges)
        assertTrue(DifferenceType.CATEGORY_CHANGED in aChanges)
        assertTrue(DifferenceType.METADATA_CHANGED in aChanges)
        assertTrue(DifferenceType.DOCUMENT_CONTENT_CHANGED in aChanges)
        assertTrue(result.items.single { it.item?.sourceId == "item-new" }.requiresAnalysis)
        assertEquals(setOf(DifferenceType.ITEM_WITHDRAWN), result.items.single { it.previous?.sourceId == "item-b" }.differences)
    }

    @Test
    fun `classifies document additions and removals`() {
        val original = item("item-a", sequence = 1)
        val baseline = baseline(original)
        val added = original.copy(
            documents = original.documents + original.documents.single().copy(sourceId = "doc-extra"),
            fingerprint = AgendaParser.sha256("document-added"),
        )
        val addedResult = comparator.compare(agenda, PublicationStatus.CURRENT, listOf(added), baseline)
        assertTrue(DifferenceType.DOCUMENT_ADDED in addedResult.differences)

        val removed = original.copy(documents = emptyList(), fingerprint = AgendaParser.sha256("document-removed"))
        val removedResult = comparator.compare(agenda, PublicationStatus.CURRENT, listOf(removed), baseline)
        assertTrue(DifferenceType.DOCUMENT_REMOVED in removedResult.differences)
    }

    @Test
    fun `moving an unchanged item creates a revision but no reanalysis`() {
        val original = item("item-a", sequence = 1)
        val moved = original.copy(sequence = 9, fingerprint = AgendaParser.sha256("item-a|9"))

        val result = comparator.compare(agenda, PublicationStatus.CURRENT, listOf(moved), baseline(original))
        val difference = result.items.single()

        assertEquals(setOf(DifferenceType.ITEM_MOVED), difference.differences)
        assertFalse(difference.requiresAnalysis)
        assertFalse(result.requiresAnalysis)
    }

    @Test
    fun `equivalent parsed formatting creates no revision differences`() {
        val current = item("item-a", sequence = 1)
        val result = comparator.compare(
            agenda.copy(committee = "  Commissie   Ruimte "),
            PublicationStatus.CURRENT,
            listOf(current),
            baseline(current),
        )
        assertTrue(result.unchanged)
        assertFalse(result.requiresAnalysis)
    }

    @Test
    fun `all documented revision scenarios are present`() {
        val root = requireNotNull(javaClass.getResourceAsStream("/fixtures/revisions/scenarios.json")).use {
            jacksonObjectMapper().readTree(it)
        }
        val names: Set<String> = root.path("scenarios").iterator().asSequence()
            .map { it.path("name").asText() }
            .toSet()
        assertEquals(
            setOf(
                "preview-to-published", "preview-new-info", "item-added", "item-withdrawn", "item-moved",
                "category-changed", "metadata-changed", "document-added", "document-removed",
                "same-url-new-bytes", "formatting-only",
            ),
            names,
        )
    }

    private fun item(
        sourceId: String,
        sequence: Int,
        category: AgendaCategory = AgendaCategory.A,
        hash: String = "a",
    ) = RevisionItem(
        agendaItemId = UUID.nameUUIDFromBytes(sourceId.toByteArray()),
        sourceId = sourceId,
        parentSourceId = "section-${category.name.lowercase()}",
        sequence = sequence,
        displayNumber = sequence.toString(),
        category = category,
        title = "Voorstel $sourceId",
        explanation = "Toelichting",
        treatmentProposal = "Bespreken",
        sourceUrl = URI("https://noordholland.bestuurlijkeinformatie.nl/Agenda/Index/meeting-future#$sourceId"),
        sourceState = SourceState.CURRENT,
        documents = listOf(
            RevisionDocument(
                "doc-$sourceId",
                "Document $sourceId",
                URI("https://noordholland.bestuurlijkeinformatie.nl/Document/View/doc-$sourceId"),
                hash.repeat(64),
                100,
            ),
        ),
        fingerprint = AgendaParser.sha256("$sourceId|$sequence|$category|$hash"),
    )

    private fun baseline(vararg items: RevisionItem) = RevisionBaseline(
        UUID.randomUUID(),
        1,
        PublicationStatus.CURRENT,
        comparator.meetingFingerprint(agenda, PublicationStatus.CURRENT, items.toList()),
        items.associateBy(RevisionItem::sourceId),
    )

    private fun resource(name: String): String = requireNotNull(
        javaClass.getResource("/fixtures/meetings/$name"),
    ).readText()

    companion object {
        private val MEETING_URL = URI("https://noordholland.bestuurlijkeinformatie.nl/Agenda/Index/meeting-future")
    }
}
