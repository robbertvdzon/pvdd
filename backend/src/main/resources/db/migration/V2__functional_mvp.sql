CREATE TABLE meeting (
    id UUID PRIMARY KEY,
    source_id VARCHAR(160) NOT NULL UNIQUE,
    committee VARCHAR(160) NOT NULL,
    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at TIMESTAMP WITH TIME ZONE,
    location TEXT,
    title TEXT NOT NULL,
    source_url TEXT NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL,
    checked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    imported_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    error_code VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT meeting_status_check CHECK (status IN (
        'DISCOVERED', 'AGENDA_UNPUBLISHED', 'IMPORTING', 'ANALYSING', 'COMPLETE', 'PARTIAL', 'FAILED'
    ))
);

CREATE TABLE agenda_item (
    id UUID PRIMARY KEY,
    meeting_id UUID NOT NULL REFERENCES meeting(id),
    source_id VARCHAR(160) NOT NULL,
    parent_source_id VARCHAR(160),
    sequence_number INTEGER NOT NULL,
    display_number VARCHAR(40),
    category VARCHAR(20) NOT NULL,
    title TEXT NOT NULL,
    explanation TEXT,
    treatment_proposal TEXT,
    source_url TEXT NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    substantive BOOLEAN NOT NULL,
    import_status VARCHAR(40) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT agenda_item_category_check CHECK (category IN ('A', 'B', 'C', 'OTHER')),
    CONSTRAINT agenda_item_import_status_check CHECK (import_status IN (
        'PENDING', 'IN_PROGRESS', 'COMPLETE', 'PARTIAL', 'FAILED'
    )),
    CONSTRAINT agenda_item_source_unique UNIQUE (meeting_id, source_id)
);
CREATE INDEX agenda_item_meeting_sequence_idx ON agenda_item(meeting_id, sequence_number);

CREATE TABLE source_document (
    id UUID PRIMARY KEY,
    agenda_item_id UUID NOT NULL REFERENCES agenda_item(id),
    source_id VARCHAR(160) NOT NULL,
    name TEXT NOT NULL,
    source_url TEXT NOT NULL,
    declared_mime_type VARCHAR(160),
    detected_mime_type VARCHAR(160),
    sha256 VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    extraction_status VARCHAR(40) NOT NULL,
    extracted_sections JSONB NOT NULL DEFAULT '[]'::jsonb,
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    error_code VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT source_document_status_check CHECK (extraction_status IN (
        'PENDING', 'EXTRACTED', 'OCR_REQUIRED', 'UNSUPPORTED', 'TOO_LARGE',
        'DOWNLOAD_FAILED', 'INVALID_CONTENT'
    )),
    CONSTRAINT source_document_version_unique UNIQUE (agenda_item_id, source_id, sha256)
);
CREATE INDEX source_document_item_idx ON source_document(agenda_item_id);

CREATE TABLE analysis_run (
    id UUID PRIMARY KEY,
    meeting_id UUID NOT NULL REFERENCES meeting(id),
    agenda_item_id UUID NOT NULL REFERENCES agenda_item(id),
    source_fingerprint VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(80) NOT NULL,
    selection_version VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL UNIQUE,
    runtime_job_id VARCHAR(160),
    status VARCHAR(40) NOT NULL,
    outbox_status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    error_code VARCHAR(120),
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT analysis_run_status_check CHECK (status IN (
        'PENDING', 'QUEUED', 'WAITING_FOR_WORKER', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT analysis_run_outbox_check CHECK (outbox_status IN ('PENDING', 'SUBMITTED', 'COMPLETE', 'FAILED'))
);
CREATE INDEX analysis_run_active_idx ON analysis_run(status, updated_at);
CREATE INDEX analysis_run_item_idx ON analysis_run(agenda_item_id, created_at DESC);

CREATE TABLE agenda_item_advice (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL UNIQUE REFERENCES analysis_run(id),
    agenda_item_id UUID NOT NULL REFERENCES agenda_item(id),
    category VARCHAR(20) NOT NULL,
    advice JSONB NOT NULL,
    citations JSONB NOT NULL,
    provider VARCHAR(40) NOT NULL,
    model VARCHAR(120) NOT NULL,
    prompt_version VARCHAR(80) NOT NULL,
    source_fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT agenda_item_advice_category_check CHECK (category IN ('A', 'B', 'C'))
);
CREATE INDEX agenda_item_advice_item_idx ON agenda_item_advice(agenda_item_id, created_at DESC);

CREATE TABLE policy_source (
    id UUID PRIMARY KEY,
    source_url TEXT NOT NULL,
    source_sha256 VARCHAR(64) NOT NULL,
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    page_number INTEGER NOT NULL,
    chunk_sequence INTEGER NOT NULL,
    heading TEXT,
    chunk_text TEXT NOT NULL,
    themes TEXT[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT policy_source_page_check CHECK (page_number > 0),
    CONSTRAINT policy_source_chunk_unique UNIQUE (source_sha256, page_number, chunk_sequence)
);
CREATE INDEX policy_source_current_idx ON policy_source(source_sha256, page_number, chunk_sequence);

INSERT INTO application_metadata(metadata_key, metadata_value)
VALUES ('functional-schema-version', '2')
ON CONFLICT (metadata_key) DO UPDATE
SET metadata_value = EXCLUDED.metadata_value, updated_at = CURRENT_TIMESTAMP;
