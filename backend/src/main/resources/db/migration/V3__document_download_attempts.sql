ALTER TABLE source_document ALTER COLUMN sha256 DROP NOT NULL;
ALTER TABLE source_document ALTER COLUMN size_bytes DROP NOT NULL;
ALTER TABLE source_document ALTER COLUMN fetched_at DROP NOT NULL;
ALTER TABLE source_document DROP CONSTRAINT source_document_version_unique;

CREATE UNIQUE INDEX source_document_version_unique
    ON source_document(agenda_item_id, source_id, sha256)
    WHERE sha256 IS NOT NULL;

CREATE UNIQUE INDEX source_document_failed_unique
    ON source_document(agenda_item_id, source_id)
    WHERE sha256 IS NULL;
