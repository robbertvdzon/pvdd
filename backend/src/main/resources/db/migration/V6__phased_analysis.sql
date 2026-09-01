ALTER TABLE analysis_run ADD COLUMN run_type VARCHAR(30) NOT NULL DEFAULT 'FINAL_ADVICE';
ALTER TABLE analysis_run ADD COLUMN phase_index INTEGER NOT NULL DEFAULT 0;
ALTER TABLE analysis_run ADD COLUMN parent_run_id UUID REFERENCES analysis_run(id);
ALTER TABLE analysis_run ADD COLUMN phase_result JSONB;

ALTER TABLE analysis_run ADD CONSTRAINT analysis_run_type_check
    CHECK (run_type IN ('FINAL_ADVICE', 'SOURCE_NOTES'));
ALTER TABLE analysis_run ADD CONSTRAINT analysis_run_phase_check
    CHECK (
        (run_type = 'FINAL_ADVICE' AND parent_run_id IS NULL AND phase_index = 0) OR
        (run_type = 'SOURCE_NOTES' AND parent_run_id IS NOT NULL AND phase_index > 0)
    );

CREATE INDEX analysis_run_parent_phase_idx ON analysis_run(parent_run_id, phase_index);
