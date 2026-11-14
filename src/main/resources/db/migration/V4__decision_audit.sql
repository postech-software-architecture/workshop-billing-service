CREATE TABLE budget_decisions (
 budget_id UUID PRIMARY KEY REFERENCES budgets(id), decision TEXT NOT NULL,
 actor TEXT NOT NULL, decided_at TIMESTAMPTZ NOT NULL DEFAULT now());
UPDATE foundation_state SET schema_version=4;
