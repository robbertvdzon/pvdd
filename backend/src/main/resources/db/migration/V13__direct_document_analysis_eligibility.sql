CREATE VIEW agenda_item_document_status AS
WITH current_document_versions AS (
    SELECT
        item.id AS agenda_item_id,
        document_revision.id AS document_id,
        document_version.extraction_status,
        document_version.extracted_sections
    FROM agenda_item item
    JOIN meeting ON meeting.id = item.meeting_id
    JOIN meeting_revision
        ON meeting_revision.meeting_id = meeting.id
       AND meeting_revision.revision_number = meeting.current_revision_number
    JOIN agenda_item_revision
        ON agenda_item_revision.meeting_revision_id = meeting_revision.id
       AND agenda_item_revision.agenda_item_id = item.id
       AND agenda_item_revision.source_state <> 'WITHDRAWN'
    JOIN document_revision
        ON document_revision.agenda_item_revision_id = agenda_item_revision.id
       AND document_revision.source_state = 'CURRENT'
    LEFT JOIN LATERAL (
        SELECT source_document.extraction_status, source_document.extracted_sections
        FROM source_document
        WHERE source_document.agenda_item_id = item.id
          AND source_document.source_id = document_revision.source_id
          AND source_document.sha256 IS NOT DISTINCT FROM document_revision.sha256
        ORDER BY source_document.created_at DESC
        LIMIT 1
    ) document_version ON TRUE
), legacy_document_versions AS (
    SELECT DISTINCT ON (item.id, source_document.source_id)
        item.id AS agenda_item_id,
        source_document.id AS document_id,
        source_document.extraction_status,
        source_document.extracted_sections
    FROM agenda_item item
    JOIN meeting ON meeting.id = item.meeting_id AND meeting.current_revision_number = 0
    JOIN source_document ON source_document.agenda_item_id = item.id
    ORDER BY item.id, source_document.source_id, source_document.created_at DESC
), document_versions AS (
    SELECT * FROM current_document_versions
    UNION ALL
    SELECT * FROM legacy_document_versions
)
SELECT
    item.id AS agenda_item_id,
    COUNT(document_versions.document_id)::INTEGER AS document_count,
    COUNT(document_versions.document_id) FILTER (
        WHERE document_versions.extraction_status = 'EXTRACTED'
          AND jsonb_array_length(document_versions.extracted_sections) > 0
    )::INTEGER AS readable_document_count
FROM agenda_item item
LEFT JOIN document_versions ON document_versions.agenda_item_id = item.id
GROUP BY item.id;

UPDATE agenda_item_advice advice
SET actuality = 'STALE'
FROM agenda_item_document_status documents
WHERE documents.agenda_item_id = advice.agenda_item_id
  AND documents.readable_document_count = 0
  AND advice.actuality = 'CURRENT';

INSERT INTO application_metadata(metadata_key, metadata_value)
VALUES ('direct-document-analysis-schema-version', '13')
ON CONFLICT (metadata_key) DO UPDATE
SET metadata_value = EXCLUDED.metadata_value, updated_at = CURRENT_TIMESTAMP;
