ALTER TABLE meeting ADD COLUMN publication_status VARCHAR(20) NOT NULL DEFAULT 'CURRENT';
ALTER TABLE meeting ADD COLUMN current_revision_number INTEGER NOT NULL DEFAULT 0;
ALTER TABLE meeting ADD COLUMN canonical_fingerprint VARCHAR(64);

ALTER TABLE agenda_item ADD COLUMN source_state VARCHAR(20) NOT NULL DEFAULT 'CURRENT';
ALTER TABLE agenda_item ADD COLUMN current_fingerprint VARCHAR(64);

ALTER TABLE agenda_item_advice ADD COLUMN actuality VARCHAR(20) NOT NULL DEFAULT 'CURRENT';

ALTER TABLE meeting ADD CONSTRAINT meeting_publication_status_check
    CHECK (publication_status IN ('PREVIEW', 'CURRENT'));
ALTER TABLE agenda_item ADD CONSTRAINT agenda_item_source_state_check
    CHECK (source_state IN ('PREVIEW', 'CURRENT', 'WITHDRAWN'));
ALTER TABLE agenda_item_advice ADD CONSTRAINT agenda_item_advice_actuality_check
    CHECK (actuality IN ('CURRENT', 'STALE', 'WITHDRAWN'));

CREATE TABLE meeting_revision (
    id UUID PRIMARY KEY,
    meeting_id UUID NOT NULL REFERENCES meeting(id),
    revision_number INTEGER NOT NULL,
    publication_status VARCHAR(20) NOT NULL,
    revision_status VARCHAR(30) NOT NULL,
    canonical_fingerprint VARCHAR(64) NOT NULL,
    previous_revision_id UUID REFERENCES meeting_revision(id),
    difference_types TEXT[] NOT NULL DEFAULT '{}',
    source_url TEXT NOT NULL,
    checked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT meeting_revision_publication_check CHECK (publication_status IN ('PREVIEW', 'CURRENT')),
    CONSTRAINT meeting_revision_status_check CHECK (
        revision_status IN ('PREVIEW', 'CURRENT', 'CHANGED', 'REPROCESSING', 'SUPERSEDED', 'FAILED')
    ),
    CONSTRAINT meeting_revision_number_unique UNIQUE (meeting_id, revision_number)
);
CREATE INDEX meeting_revision_meeting_idx ON meeting_revision(meeting_id, revision_number DESC);

CREATE TABLE source_check (
    id UUID PRIMARY KEY,
    meeting_id UUID NOT NULL REFERENCES meeting(id),
    checked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    publication_status VARCHAR(20) NOT NULL,
    canonical_fingerprint VARCHAR(64) NOT NULL,
    outcome VARCHAR(40) NOT NULL,
    error_code VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT source_check_publication_check CHECK (publication_status IN ('PREVIEW', 'CURRENT'))
);
CREATE INDEX source_check_meeting_idx ON source_check(meeting_id, checked_at DESC);

CREATE TABLE agenda_item_revision (
    id UUID PRIMARY KEY,
    meeting_revision_id UUID NOT NULL REFERENCES meeting_revision(id),
    agenda_item_id UUID REFERENCES agenda_item(id),
    source_id VARCHAR(160) NOT NULL,
    parent_source_id VARCHAR(160),
    sequence_number INTEGER NOT NULL,
    display_number VARCHAR(40),
    category VARCHAR(20) NOT NULL,
    title TEXT NOT NULL,
    explanation TEXT,
    treatment_proposal TEXT,
    source_url TEXT NOT NULL,
    item_fingerprint VARCHAR(64) NOT NULL,
    source_state VARCHAR(20) NOT NULL,
    difference_types TEXT[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT agenda_item_revision_state_check CHECK (source_state IN ('PREVIEW', 'CURRENT', 'WITHDRAWN')),
    CONSTRAINT agenda_item_revision_unique UNIQUE (meeting_revision_id, source_id)
);
CREATE INDEX agenda_item_revision_source_idx ON agenda_item_revision(source_id, meeting_revision_id);

CREATE TABLE document_revision (
    id UUID PRIMARY KEY,
    agenda_item_revision_id UUID NOT NULL REFERENCES agenda_item_revision(id),
    source_id VARCHAR(160) NOT NULL,
    name TEXT NOT NULL,
    source_url TEXT NOT NULL,
    etag TEXT,
    last_modified TEXT,
    size_bytes BIGINT,
    sha256 VARCHAR(64),
    source_state VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT document_revision_state_check CHECK (source_state IN ('CURRENT', 'WITHDRAWN')),
    CONSTRAINT document_revision_unique UNIQUE (agenda_item_revision_id, source_id)
);

INSERT INTO application_metadata(metadata_key, metadata_value)
VALUES ('source-revision-schema-version', '7')
ON CONFLICT (metadata_key) DO UPDATE
SET metadata_value = EXCLUDED.metadata_value, updated_at = CURRENT_TIMESTAMP;
