package nl.vdzon.pvdd.meetings

import java.net.URI
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class SourceRevisionRepository(private val jdbc: JdbcTemplate) : SourceRevisionStore {
    override fun baseline(meetingSourceId: String): RevisionBaseline? {
        val revision = jdbc.query(
            """
            SELECT mr.id, mr.revision_number, mr.publication_status, mr.canonical_fingerprint,
                   mr.revision_status
            FROM meeting_revision mr JOIN meeting m ON m.id = mr.meeting_id
            WHERE m.source_id = ? ORDER BY mr.revision_number DESC LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                RevisionHeader(
                    rs.getObject("id", UUID::class.java),
                    rs.getInt("revision_number"),
                    PublicationStatus.valueOf(rs.getString("publication_status")),
                    rs.getString("canonical_fingerprint"),
                    RevisionStatus.valueOf(rs.getString("revision_status")),
                )
            },
            meetingSourceId,
        ).singleOrNull() ?: return null
        val items = jdbc.query(
            """
            SELECT id, agenda_item_id, source_id, parent_source_id, sequence_number, display_number,
                   category, title, explanation, treatment_proposal, source_url, item_fingerprint, source_state
            FROM agenda_item_revision WHERE meeting_revision_id = ? AND source_state <> 'WITHDRAWN'
            ORDER BY sequence_number
            """.trimIndent(),
            { rs, _ ->
                val revisionItemId = rs.getObject("id", UUID::class.java)
                RevisionItem(
                    agendaItemId = rs.getObject("agenda_item_id", UUID::class.java),
                    sourceId = rs.getString("source_id"),
                    parentSourceId = rs.getString("parent_source_id"),
                    sequence = rs.getInt("sequence_number"),
                    displayNumber = rs.getString("display_number"),
                    category = AgendaCategory.valueOf(rs.getString("category")),
                    title = rs.getString("title"),
                    explanation = rs.getString("explanation"),
                    treatmentProposal = rs.getString("treatment_proposal"),
                    sourceUrl = URI(rs.getString("source_url")),
                    sourceState = SourceState.valueOf(rs.getString("source_state")),
                    documents = documents(revisionItemId),
                    fingerprint = rs.getString("item_fingerprint"),
                )
            },
            revision.id,
        ).associateBy(RevisionItem::sourceId)
        return RevisionBaseline(
            revision.id,
            revision.number,
            revision.publicationStatus,
            revision.fingerprint,
            items,
            revision.status,
        )
    }

    @Transactional
    override fun record(
        meetingId: UUID,
        agenda: ParsedMeetingAgenda,
        publicationStatus: PublicationStatus,
        items: List<RevisionItem>,
        comparison: RevisionComparison,
        checkedAt: Instant,
    ): StoredRevision {
        val previous = baseline(agenda.sourceId)
        jdbc.update(
            """
            INSERT INTO source_check(id, meeting_id, checked_at, publication_status, canonical_fingerprint, outcome)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(), meetingId, Timestamp.from(checkedAt), publicationStatus.name,
            comparison.canonicalFingerprint, if (comparison.unchanged) "UNCHANGED" else "CHANGED",
        )
        if (previous?.publicationStatus == publicationStatus && previous.canonicalFingerprint == comparison.canonicalFingerprint) {
            jdbc.update(
                "UPDATE meeting SET checked_at = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                Timestamp.from(checkedAt), meetingId,
            )
            return StoredRevision(previous.revisionId, previous.revisionNumber, comparison)
        }

        val number = (previous?.revisionNumber ?: 0) + 1
        val revisionId = UUID.randomUUID()
        val status = when {
            comparison.requiresAnalysis -> RevisionStatus.REPROCESSING
            publicationStatus == PublicationStatus.PREVIEW -> RevisionStatus.PREVIEW
            comparison.differences.isEmpty() -> RevisionStatus.CURRENT
            else -> RevisionStatus.CHANGED
        }
        jdbc.update(
            """
            INSERT INTO meeting_revision(
                id, meeting_id, revision_number, publication_status, revision_status,
                canonical_fingerprint, previous_revision_id, difference_types, source_url, checked_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS text[]), ?, ?)
            """.trimIndent(),
            revisionId, meetingId, number, publicationStatus.name, status.name,
            comparison.canonicalFingerprint, previous?.revisionId,
            pgArray(comparison.differences),
            agenda.sourceUrl.toString(), Timestamp.from(checkedAt),
        )

        comparison.items.forEach { difference ->
            val source = difference.item ?: requireNotNull(difference.previous).copy(sourceState = SourceState.WITHDRAWN)
            val itemRevisionId = UUID.randomUUID()
            jdbc.update(
                """
                INSERT INTO agenda_item_revision(
                    id, meeting_revision_id, agenda_item_id, source_id, parent_source_id, sequence_number,
                    display_number, category, title, explanation, treatment_proposal, source_url,
                    item_fingerprint, source_state, difference_types
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS text[]))
                """.trimIndent(),
                itemRevisionId, revisionId, source.agendaItemId, source.sourceId, source.parentSourceId,
                source.sequence, source.displayNumber, source.category.name, source.title, source.explanation,
                source.treatmentProposal, source.sourceUrl.toString(), source.fingerprint, source.sourceState.name,
                pgArray(difference.differences),
            )
            source.documents.forEach { document ->
                jdbc.update(
                    """
                    INSERT INTO document_revision(
                        id, agenda_item_revision_id, source_id, name, source_url, etag, last_modified,
                        size_bytes, sha256, source_state
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    UUID.randomUUID(), itemRevisionId, document.sourceId, document.name,
                    document.sourceUrl.toString(), document.etag, document.lastModified,
                    document.sizeBytes, document.sha256,
                    if (source.sourceState == SourceState.WITHDRAWN) SourceState.WITHDRAWN.name else SourceState.CURRENT.name,
                )
            }
            when {
                source.sourceState == SourceState.WITHDRAWN -> {
                    jdbc.update("UPDATE agenda_item SET source_state = 'WITHDRAWN', updated_at = CURRENT_TIMESTAMP WHERE id = ?", source.agendaItemId)
                    jdbc.update("UPDATE agenda_item_advice SET actuality = 'WITHDRAWN' WHERE agenda_item_id = ?", source.agendaItemId)
                }
                difference.requiresAnalysis -> jdbc.update(
                    "UPDATE agenda_item_advice SET actuality = 'STALE' WHERE agenda_item_id = ? AND actuality = 'CURRENT'",
                    source.agendaItemId,
                )
                else -> jdbc.update(
                    """
                    UPDATE agenda_item_advice advice SET actuality = CASE
                        WHEN advice.analysis_run_id = (
                            SELECT id FROM analysis_run
                            WHERE agenda_item_id = ? AND run_type = 'FINAL_ADVICE' AND status = 'SUCCEEDED'
                            ORDER BY created_at DESC, id DESC LIMIT 1
                        ) THEN 'CURRENT'
                        ELSE 'STALE'
                    END
                    WHERE advice.agenda_item_id = ?
                    """.trimIndent(),
                    source.agendaItemId,
                    source.agendaItemId,
                )
            }
        }
        jdbc.update(
            """
            UPDATE meeting SET publication_status = ?, current_revision_number = ?,
                canonical_fingerprint = ?, checked_at = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """.trimIndent(),
            publicationStatus.name, number, comparison.canonicalFingerprint, Timestamp.from(checkedAt), meetingId,
        )
        return StoredRevision(revisionId, number, comparison)
    }

    private fun documents(itemRevisionId: UUID): List<RevisionDocument> = jdbc.query(
        """
        SELECT source_id, name, source_url, sha256, size_bytes, etag, last_modified
        FROM document_revision WHERE agenda_item_revision_id = ? AND source_state = 'CURRENT'
        ORDER BY source_id
        """.trimIndent(),
        { rs, _ ->
            RevisionDocument(
                rs.getString("source_id"), rs.getString("name"), URI(rs.getString("source_url")),
                rs.getString("sha256"), (rs.getObject("size_bytes") as? Number)?.toLong(),
                rs.getString("etag"), rs.getString("last_modified"),
            )
        },
        itemRevisionId,
    )

    private data class RevisionHeader(
        val id: UUID,
        val number: Int,
        val publicationStatus: PublicationStatus,
        val fingerprint: String,
        val status: RevisionStatus,
    )

    private fun pgArray(values: Set<DifferenceType>): String = values.map(DifferenceType::name)
        .sorted()
        .joinToString(prefix = "{", postfix = "}")
}
