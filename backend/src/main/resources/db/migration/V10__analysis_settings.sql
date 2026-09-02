CREATE TABLE application_setting (
    setting_key VARCHAR(120) PRIMARY KEY,
    setting_value TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(320) NOT NULL
);

INSERT INTO application_setting(setting_key, setting_value, updated_by)
VALUES (
    'analysis.additional-instructions',
    'Adviseer een C-stuk alleen naar B te verplaatsen wanneer bespreking aantoonbare politieke meerwaarde heeft, bijvoorbeeld omdat een politiek besluit, toezegging, bijsturing of openbaar debat nodig is. Bij twijfel blijft het een C-stuk. Motiveer de afweging concreet en baseer die uitsluitend op de aangeleverde bronnen.',
    'system'
);

ALTER TABLE analysis_run
    ADD COLUMN analysis_guidance TEXT NOT NULL DEFAULT '';
