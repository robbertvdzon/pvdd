-- Het model dat de runtime voor een run gebruikte; het model is per taak te kiezen, dus dit is nodig
-- om later kwaliteit en kosten per model te kunnen vergelijken.
ALTER TABLE analysis_run
    ADD COLUMN execution_vendor_id VARCHAR(100),
    ADD COLUMN execution_model VARCHAR(160);
