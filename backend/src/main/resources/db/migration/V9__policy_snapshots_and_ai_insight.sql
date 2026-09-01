CREATE TABLE policy_sync_run (
    id UUID PRIMARY KEY,
    trigger_type VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL UNIQUE,
    status VARCHAR(30) NOT NULL,
    runtime_job_id VARCHAR(160),
    candidate_snapshot_id UUID,
    source_count INTEGER NOT NULL DEFAULT 0,
    new_count INTEGER NOT NULL DEFAULT 0,
    changed_count INTEGER NOT NULL DEFAULT 0,
    unchanged_count INTEGER NOT NULL DEFAULT 0,
    disappeared_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT policy_sync_trigger_check CHECK (trigger_type IN ('MONTHLY', 'MANUAL')),
    CONSTRAINT policy_sync_status_check CHECK (status IN (
        'PENDING', 'RUNNING', 'QUEUED', 'WAITING_FOR_WORKER', 'SUCCEEDED', 'FAILED', 'CANCELLED'
    ))
);
CREATE UNIQUE INDEX policy_sync_one_active_idx ON policy_sync_run ((1))
    WHERE status IN ('PENDING', 'RUNNING', 'QUEUED', 'WAITING_FOR_WORKER');
CREATE INDEX policy_sync_finished_idx ON policy_sync_run(completed_at DESC, id DESC);

CREATE TABLE policy_web_source (
    id UUID PRIMARY KEY,
    canonical_url TEXT NOT NULL UNIQUE,
    source_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CURRENT',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT policy_web_source_type_check CHECK (source_type IN (
        'PROGRAMME', 'IDEAL', 'POLITICAL_WORK', 'NEWS'
    )),
    CONSTRAINT policy_web_source_status_check CHECK (status IN ('CURRENT', 'DISAPPEARED', 'REJECTED'))
);

CREATE TABLE policy_web_revision (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES policy_web_source(id),
    sha256 VARCHAR(64) NOT NULL,
    title TEXT NOT NULL,
    publication_date DATE,
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    content_type VARCHAR(160) NOT NULL,
    size_bytes BIGINT NOT NULL,
    etag TEXT,
    last_modified TEXT,
    extracted_text TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT policy_web_revision_unique UNIQUE (source_id, sha256)
);
CREATE INDEX policy_web_revision_source_idx ON policy_web_revision(source_id, fetched_at DESC);

CREATE TABLE policy_snapshot (
    id UUID PRIMARY KEY,
    version_number INTEGER NOT NULL UNIQUE,
    fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    activated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT policy_snapshot_status_check CHECK (status IN ('CANDIDATE', 'ACTIVE', 'SUPERSEDED', 'FAILED'))
);
CREATE UNIQUE INDEX policy_snapshot_active_idx ON policy_snapshot ((1)) WHERE status = 'ACTIVE';

ALTER TABLE policy_sync_run ADD CONSTRAINT policy_sync_candidate_fk
    FOREIGN KEY (candidate_snapshot_id) REFERENCES policy_snapshot(id);

CREATE TABLE policy_snapshot_source (
    snapshot_id UUID NOT NULL REFERENCES policy_snapshot(id),
    revision_id UUID NOT NULL REFERENCES policy_web_revision(id),
    PRIMARY KEY (snapshot_id, revision_id)
);

CREATE TABLE policy_position (
    id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL REFERENCES policy_snapshot(id),
    title TEXT NOT NULL,
    summary TEXT NOT NULL,
    themes TEXT[] NOT NULL DEFAULT '{}',
    direction TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    source_date DATE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT policy_position_status_check CHECK (status IN (
        'CURRENT', 'CHANGED', 'POTENTIAL_CONFLICT', 'EXPIRED'
    ))
);
CREATE INDEX policy_position_snapshot_idx ON policy_position(snapshot_id, title);

CREATE TABLE policy_position_reference (
    position_id UUID NOT NULL REFERENCES policy_position(id),
    revision_id UUID NOT NULL REFERENCES policy_web_revision(id),
    page_number INTEGER NOT NULL DEFAULT 0,
    section TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (position_id, revision_id, page_number, section)
);

INSERT INTO application_metadata(metadata_key, metadata_value)
VALUES ('policy-insight-schema-version', '9')
ON CONFLICT (metadata_key) DO UPDATE
SET metadata_value = EXCLUDED.metadata_value, updated_at = CURRENT_TIMESTAMP;
