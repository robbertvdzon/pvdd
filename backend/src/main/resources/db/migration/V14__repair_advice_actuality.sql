UPDATE agenda_item_advice advice
SET actuality = CASE
    WHEN item.source_state = 'WITHDRAWN' THEN 'WITHDRAWN'
    WHEN advice.analysis_run_id = (
        SELECT run.id
        FROM analysis_run run
        WHERE run.agenda_item_id = advice.agenda_item_id
          AND run.run_type = 'FINAL_ADVICE'
        ORDER BY run.created_at DESC, run.id DESC
        LIMIT 1
    ) AND EXISTS (
        SELECT 1
        FROM analysis_run run
        WHERE run.id = advice.analysis_run_id
          AND run.status = 'SUCCEEDED'
    ) THEN 'CURRENT'
    ELSE 'STALE'
END
FROM agenda_item item
WHERE item.id = advice.agenda_item_id;
