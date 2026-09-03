ALTER TABLE analysis_run
    ADD COLUMN runtime_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_runtime_attempt_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN retry_of_run_id UUID REFERENCES analysis_run(id);

ALTER TABLE analysis_run ADD CONSTRAINT analysis_run_runtime_attempt_count_check
    CHECK (runtime_attempt_count >= 0);

CREATE INDEX analysis_run_runtime_retry_idx
    ON analysis_run(status, outbox_status, next_runtime_attempt_at, created_at);

CREATE UNIQUE INDEX analysis_run_single_manual_retry_idx
    ON analysis_run(retry_of_run_id) WHERE retry_of_run_id IS NOT NULL;
