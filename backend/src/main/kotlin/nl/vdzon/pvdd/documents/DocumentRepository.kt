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
            ON CONFLICT (agenda_item_id, source_id, sha256) DO NOTHING
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

    fun countVersions(agendaItemId: UUID, sourceId: String): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM source_document WHERE agenda_item_id = ? AND source_id = ?",
        Int::class.java,
        agendaItemId,
        sourceId,
    ) ?: 0
}
