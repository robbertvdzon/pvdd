ALTER TABLE meeting
    ADD COLUMN commissie text NOT NULL DEFAULT 'RUIMTE',
    ADD CONSTRAINT meeting_commissie_check CHECK (commissie IN ('RUIMTE', 'BESTUUR'));
