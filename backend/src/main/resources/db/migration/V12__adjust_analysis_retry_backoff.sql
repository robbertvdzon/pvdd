UPDATE analysis_run
SET next_runtime_attempt_at = GREATEST(
    next_runtime_attempt_at,
    updated_at + CASE runtime_attempt_count
        WHEN 1 THEN INTERVAL '15 minutes'
        WHEN 2 THEN INTERVAL '120 minutes'
    END
)
WHERE status = 'PENDING'
  AND next_runtime_attempt_at IS NOT NULL
  AND runtime_attempt_count IN (1, 2);
