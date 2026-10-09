-- Final advice runs whose source-notes phases all failed to submit stayed PENDING forever,
-- so "retry failed analyses" skipped them. Mark them FAILED so they can be retried.
UPDATE analysis_run final
SET status = 'FAILED', outbox_status = 'FAILED', error_code = 'RUNTIME_UNAVAILABLE',
    completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP), updated_at = CURRENT_TIMESTAMP
WHERE final.run_type = 'FINAL_ADVICE' AND final.status = 'PENDING' AND final.prompt_text IS NULL
  AND EXISTS (SELECT 1 FROM analysis_run phase WHERE phase.parent_run_id = final.id)
  AND NOT EXISTS (
      SELECT 1 FROM analysis_run phase
      WHERE phase.parent_run_id = final.id AND phase.status NOT IN ('FAILED', 'CANCELLED')
  )
  AND EXISTS (
      SELECT 1 FROM analysis_run phase
      WHERE phase.parent_run_id = final.id AND phase.status = 'FAILED' AND phase.error_code = 'RUNTIME_UNAVAILABLE'
  );
