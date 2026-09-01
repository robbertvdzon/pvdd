package nl.vdzon.pvdd.meetings

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import nl.vdzon.pvdd.documents.SourceDocument
import org.springframework.stereotype.Component

enum class PublicationStatus { PREVIEW, CURRENT }
enum class RevisionStatus { PREVIEW, CURRENT, CHANGED, REPROCESSING, SUPERSEDED, FAILED }
enum class SourceState { PREVIEW, CURRENT, WITHDRAWN }

enum class DifferenceType {
    PUBLICATION_STATUS,
    ITEM_ADDED,
    ITEM_WITHDRAWN,
    ITEM_MOVED,
    CATEGORY_CHANGED,
    METADATA_CHANGED,
    DOCUMENT_ADDED,
    DOCUMENT_REMOVED,
    DOCUMENT_CONTENT_CHANGED,
}

data class RevisionDocument(
    val sourceId: String,
    val name: String,
    val sourceUrl: URI,
    val sha256: String?,
    val sizeBytes: Long?,
    val etag: String? = null,
    val lastModified: String? = null,
)

data class RevisionItem(
    val agendaItemId: UUID,
    val sourceId: String,
    val parentSourceId: String?,
    val sequence: Int,
    val displayNumber: String?,
    val category: AgendaCategory,
    val title: String,
    val explanation: String?,
    val treatmentProposal: String?,
    val sourceUrl: URI,
    val sourceState: SourceState,
    val documents: List<RevisionDocument>,
    val fingerprint: String,
)

data class RevisionBaseline(
    val revisionId: UUID,
    val revisionNumber: Int,
    val publicationStatus: PublicationStatus,
    val canonicalFingerprint: String,
    val items: Map<String, RevisionItem>,
    val revisionStatus: RevisionStatus = RevisionStatus.CURRENT,
)

data class ItemDifference(
    val item: RevisionItem?,
    val previous: RevisionItem?,
    val differences: Set<DifferenceType>,
) {
    val requiresAnalysis: Boolean
        get() = item != null && item.sourceState != SourceState.WITHDRAWN &&
            differences.any(ANALYSIS_AFFECTING_DIFFERENCES::contains)
}

private val ANALYSIS_AFFECTING_DIFFERENCES = setOf(
    DifferenceType.PUBLICATION_STATUS,
    DifferenceType.ITEM_ADDED,
    DifferenceType.CATEGORY_CHANGED,
    DifferenceType.METADATA_CHANGED,
    DifferenceType.DOCUMENT_ADDED,
    DifferenceType.DOCUMENT_REMOVED,
    DifferenceType.DOCUMENT_CONTENT_CHANGED,
)

data class RevisionComparison(
    val publicationStatus: PublicationStatus,
    val canonicalFingerprint: String,
    val differences: Set<DifferenceType>,
    val items: List<ItemDifference>,
) {
    val unchanged: Boolean get() = differences.isEmpty()
    val requiresAnalysis: Boolean get() = items.any(ItemDifference::requiresAnalysis)
}

data class StoredRevision(
    val id: UUID,
    val number: Int,
    val comparison: RevisionComparison,
)

interface SourceRevisionStore {
    fun baseline(meetingSourceId: String): RevisionBaseline?
    fun record(
        meetingId: UUID,
        agenda: ParsedMeetingAgenda,
        publicationStatus: PublicationStatus,
        items: List<RevisionItem>,
        comparison: RevisionComparison,
        checkedAt: Instant,
    ): StoredRevision
}

@Component
class AgendaRevisionComparator {
    fun currentItem(
        item: ParsedAgendaItem,
        agendaItemId: UUID,
        documents: List<SourceDocument>,
        sourceState: SourceState = SourceState.CURRENT,
    ): RevisionItem = item(
        item,
        agendaItemId,
        sourceState,
        documents.map { document ->
            RevisionDocument(
                document.sourceId,
                document.name,
                document.sourceUrl,
                document.sha256,
                document.sizeBytes,
            )
        },
    )

    fun compare(
        agenda: ParsedMeetingAgenda,
        publicationStatus: PublicationStatus,
        items: List<RevisionItem>,
        baseline: RevisionBaseline?,
    ): RevisionComparison {
        val fingerprint = meetingFingerprint(agenda, publicationStatus, items)
        if (baseline == null) {
            val firstType = if (items.isEmpty()) emptySet() else setOf(DifferenceType.ITEM_ADDED)
            return RevisionComparison(
                publicationStatus,
                fingerprint,
                firstType,
                items.map { ItemDifference(it, null, firstType) },
            )
        }
        if (baseline.publicationStatus == publicationStatus && baseline.canonicalFingerprint == fingerprint) {
            return RevisionComparison(
                publicationStatus,
                fingerprint,
                emptySet(),
                items.map { ItemDifference(it, baseline.items[it.sourceId], emptySet()) },
            )
        }

        val publicationChanged = baseline.publicationStatus != publicationStatus
        val sourceIds = (baseline.items.keys + items.map(RevisionItem::sourceId)).toSortedSet()
        val differences = sourceIds.map { sourceId ->
            val old = baseline.items[sourceId]
            val current = items.firstOrNull { it.sourceId == sourceId }
            val changes = linkedSetOf<DifferenceType>()
            if (publicationChanged) changes += DifferenceType.PUBLICATION_STATUS
            when {
                old == null -> changes += DifferenceType.ITEM_ADDED
                current == null -> changes += DifferenceType.ITEM_WITHDRAWN
                else -> changes += itemDifferences(old, current)
            }
            ItemDifference(current, old, changes)
        }
        return RevisionComparison(
            publicationStatus,
            fingerprint,
            differences.flatMap(ItemDifference::differences).toSet(),
            differences,
        )
    }

    fun meetingFingerprint(
        agenda: ParsedMeetingAgenda,
        publicationStatus: PublicationStatus,
        items: List<RevisionItem>,
    ): String = hash(
        fields(
            agenda.sourceId,
            agenda.committee,
            agenda.title,
            agenda.startsAt.toString(),
            agenda.endsAt?.toString(),
            agenda.location,
            canonicalUri(agenda.sourceUrl),
            publicationStatus.name,
            *items.sortedBy(RevisionItem::sequence).map(RevisionItem::fingerprint).toTypedArray(),
        ),
    )

    private fun item(
        item: ParsedAgendaItem,
        agendaItemId: UUID,
        state: SourceState,
        documents: List<RevisionDocument>,
    ): RevisionItem {
        val orderedDocuments = documents.sortedBy(RevisionDocument::sourceId)
        val fingerprint = hash(
            fields(
                item.sourceId,
                item.parentSourceId,
                item.sequence.toString(),
                item.displayNumber,
                item.category.name,
                item.title,
                item.explanation,
                item.treatmentProposal,
                canonicalUri(item.sourceUrl),
                *orderedDocuments.flatMap { document ->
                    listOf(document.sourceId, document.name, canonicalUri(document.sourceUrl), document.sha256, document.sizeBytes?.toString())
                }.toTypedArray(),
            ),
        )
        return RevisionItem(
            agendaItemId,
            item.sourceId,
            item.parentSourceId,
            item.sequence,
            item.displayNumber,
            item.category,
            normalize(item.title),
            item.explanation?.let(::normalize),
            item.treatmentProposal?.let(::normalize),
            URI(canonicalUri(item.sourceUrl)),
            state,
            orderedDocuments,
            fingerprint,
        )
    }

    private fun itemDifferences(old: RevisionItem, current: RevisionItem): Set<DifferenceType> = buildSet {
        if (old.sequence != current.sequence || old.parentSourceId != current.parentSourceId) add(DifferenceType.ITEM_MOVED)
        if (old.category != current.category) add(DifferenceType.CATEGORY_CHANGED)
        if (
            old.displayNumber != current.displayNumber || old.title != current.title ||
            old.explanation != current.explanation || old.treatmentProposal != current.treatmentProposal ||
            old.sourceUrl != current.sourceUrl
        ) add(DifferenceType.METADATA_CHANGED)

        val oldDocuments = old.documents.associateBy(RevisionDocument::sourceId)
        val currentDocuments = current.documents.associateBy(RevisionDocument::sourceId)
        (currentDocuments.keys - oldDocuments.keys).takeIf(Set<String>::isNotEmpty)?.let { add(DifferenceType.DOCUMENT_ADDED) }
        (oldDocuments.keys - currentDocuments.keys).takeIf(Set<String>::isNotEmpty)?.let { add(DifferenceType.DOCUMENT_REMOVED) }
        (oldDocuments.keys intersect currentDocuments.keys).forEach { sourceId ->
            val before = requireNotNull(oldDocuments[sourceId])
            val after = requireNotNull(currentDocuments[sourceId])
            if (before.sha256 != after.sha256 || before.sizeBytes != after.sizeBytes) add(DifferenceType.DOCUMENT_CONTENT_CHANGED)
        }
    }

    private fun fields(vararg values: String?): String = values.joinToString("\u001e") { value ->
        val normalized = value?.let(::normalize).orEmpty()
        "${normalized.length}:$normalized"
    }

    private fun normalize(value: String): String = value.trim().replace(WHITESPACE, " ")

    private fun canonicalUri(uri: URI): String = URI(uri.scheme?.lowercase(), null, uri.host?.lowercase(), uri.port, uri.path, null, null).toString()

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}
