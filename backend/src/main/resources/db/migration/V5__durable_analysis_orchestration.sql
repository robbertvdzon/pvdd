CREATE TABLE analysis_meeting_queue (
    meeting_id UUID PRIMARY KEY REFERENCES meeting(id),
    status VARCHAR(30) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT analysis_meeting_queue_status_check CHECK (status IN ('PENDING', 'CLAIMED', 'COMPLETE', 'FAILED'))
);

ALTER TABLE analysis_run ADD COLUMN category VARCHAR(20);
ALTER TABLE analysis_run ADD COLUMN agenda_item_source_id VARCHAR(160);
ALTER TABLE analysis_run ADD COLUMN prompt_text TEXT;
ALTER TABLE analysis_run ADD COLUMN response_schema JSONB;
ALTER TABLE analysis_run ADD COLUMN allowed_sources JSONB;
ALTER TABLE analysis_run ADD COLUMN submit_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE analysis_run DROP CONSTRAINT analysis_run_outbox_check;
ALTER TABLE analysis_run ADD CONSTRAINT analysis_run_outbox_check
    CHECK (outbox_status IN ('PENDING', 'CLAIMED', 'SUBMITTED', 'COMPLETE', 'FAILED'));
