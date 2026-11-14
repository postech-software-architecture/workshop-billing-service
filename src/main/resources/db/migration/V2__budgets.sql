CREATE TABLE budgets (
 id UUID PRIMARY KEY, os_id UUID NOT NULL, attempt_id UUID NOT NULL, saga_id UUID NOT NULL,
 correlation_id UUID NOT NULL, budget_version INTEGER NOT NULL CHECK(budget_version>0),
 snapshot TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('PENDENTE','APROVADO','REJEITADO','CANCELADO')),
 version BIGINT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), decided_at TIMESTAMPTZ,
 UNIQUE(os_id,budget_version));
CREATE TABLE budget_cancellations(id UUID PRIMARY KEY, os_id UUID NOT NULL, attempt_id UUID NOT NULL,
 saga_id UUID NOT NULL, budget_version INTEGER NOT NULL);
CREATE TABLE budget_notifications(id UUID PRIMARY KEY, budget_id UUID UNIQUE NOT NULL REFERENCES budgets(id),
 client_id UUID NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now());
UPDATE foundation_state SET schema_version=2;
