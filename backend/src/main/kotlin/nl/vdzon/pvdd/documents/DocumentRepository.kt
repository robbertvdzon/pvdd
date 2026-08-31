package nl.vdzon.pvdd.documents

import java.sql.Timestamp
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper

@Repository
class DocumentRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    fun save(document: SourceDocument): Boolean = if (document.sha256 == null) {
        saveFailedAttempt(document)
    } else {
        insertVersion(document)
    }

    fun insertVersion(document: SourceDocument): Boolean {
        requireNotNull(document.sha256) { "A stored document version requires a SHA-256" }
        requireNotNull(document.sizeBytes) { "A stored document version requires a size" }
        requireNotNull(document.fetchedAt) { "A stored document version requires a fetch time" }
        return jdbc.update(
            """
            INSERT INTO source_document(
                id, agenda_item_id, source_id, name, source_url, declared_mime_type,
                detected_mime_type, sha256, size_bytes, extraction_status, extracted_sections,
                fetched_at, error_code
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
            ON CONFLICT (agenda_item_id, source_id, sha256) WHERE sha256 IS NOT NULL DO NOTHING
            """.trimIndent(),
            document.id,
            document.agendaItemId,
            document.sourceId,
            document.name,
            document.sourceUrl.toString(),
            document.declaredMimeType,
            document.detectedMimeType,
            document.sha256,
            document.sizeBytes,
            document.extractionStatus.name,
            mapper.writeValueAsString(document.sections),
            Timestamp.from(document.fetchedAt),
            document.errorCode,
        ) == 1
    }

    private fun saveFailedAttempt(document: SourceDocument): Boolean = jdbc.update(
        """
        INSERT INTO source_document(
            id, agenda_item_id, source_id, name, source_url, declared_mime_type,
            detected_mime_type, sha256, size_bytes, extraction_status, extracted_sections,
            fetched_at, error_code
        ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, CAST(? AS jsonb), NULL, ?)
        ON CONFLICT (agenda_item_id, source_id) WHERE sha256 IS NULL DO UPDATE SET
            name = EXCLUDED.name,
            source_url = EXCLUDED.source_url,
            extraction_status = EXCLUDED.extraction_status,
            error_code = EXCLUDED.error_code,
            created_at = CURRENT_TIMESTAMP
        """.trimIndent(),
        document.id,
        document.agendaItemId,
        document.sourceId,
        document.name,
        document.sourceUrl.toString(),
        document.declaredMimeType,
        document.detectedMimeType,
        document.extractionStatus.name,
        mapper.writeValueAsString(document.sections),
        document.errorCode,
    ) == 1

    fun countVersions(agendaItemId: UUID, sourceId: String): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM source_document WHERE agenda_item_id = ? AND source_id = ?",
        Int::class.java,
        agendaItemId,
        sourceId,
    ) ?: 0

    fun findPassagesForAnalysis(agendaItemId: UUID): List<DocumentPassage> = jdbc.query(
        """
        SELECT sd.source_id, sd.source_url,
               (section ->> 'sequence')::integer AS section_sequence,
               NULLIF(section ->> 'pageNumber', '')::integer AS page_number,
               section ->> 'heading' AS heading,
               section ->> 'text' AS section_text
        FROM agenda_item target
        JOIN agenda_item source_item ON source_item.meeting_id = target.meeting_id
            AND (source_item.id = target.id OR source_item.source_id LIKE '%:meeting-documents')
        JOIN source_document sd ON sd.agenda_item_id = source_item.id
            AND sd.extraction_status = 'EXTRACTED'
        CROSS JOIN LATERAL jsonb_array_elements(sd.extracted_sections) section
        WHERE target.id = ?
        ORDER BY source_item.sequence_number, sd.source_id, section_sequence
        """.trimIndent(),
        { rs, _ ->
            DocumentPassage(
                documentSourceId = rs.getString("source_id"),
                sourceUrl = java.net.URI(rs.getString("source_url")),
                sequence = rs.getInt("section_sequence"),
                pageNumber = rs.getObject("page_number", Int::class.java),
                heading = rs.getString("heading"),
                text = rs.getString("section_text"),
            )
        },
        agendaItemId,
    )
}
