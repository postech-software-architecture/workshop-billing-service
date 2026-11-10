ALTER TABLE budget_notifications ADD COLUMN next_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE budget_notifications ADD COLUMN lease_until TIMESTAMPTZ;
ALTER TABLE budget_notifications ADD COLUMN lease_token UUID;
ALTER TABLE budget_notifications ADD COLUMN last_error TEXT;
CREATE INDEX notifications_pending ON budget_notifications(next_at) WHERE state<>'ENVIADO';
UPDATE foundation_state SET schema_version=3;
