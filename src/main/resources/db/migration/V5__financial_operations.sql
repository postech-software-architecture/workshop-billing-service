CREATE TABLE payments (
 id UUID PRIMARY KEY, budget_id UUID NOT NULL REFERENCES budgets(id), attempt_id UUID NOT NULL,
 external_reference VARCHAR(64) UNIQUE NOT NULL, total_centavos BIGINT NOT NULL CHECK(total_centavos>0),
 currency VARCHAR(3) NOT NULL CHECK(currency='BRL'), provider_order_id TEXT UNIQUE,
 state TEXT NOT NULL, version BIGINT NOT NULL DEFAULT 1,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(budget_id,attempt_id));
CREATE TABLE financial_operations (
 id UUID PRIMARY KEY, payment_id UUID NOT NULL REFERENCES payments(id), kind TEXT NOT NULL,
 idempotency_key VARCHAR(64) UNIQUE NOT NULL, state TEXT NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0, next_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 lease_until TIMESTAMPTZ, lease_token UUID, last_error TEXT,
 UNIQUE(payment_id,kind));
CREATE INDEX financial_operations_pending ON financial_operations(next_at);
CREATE TABLE provider_notifications (
 id UUID PRIMARY KEY, deduplication_key TEXT UNIQUE NOT NULL, resource_id TEXT NOT NULL,
 state TEXT NOT NULL, received_at TIMESTAMPTZ NOT NULL DEFAULT now(), processed_at TIMESTAMPTZ);
UPDATE foundation_state SET schema_version=5;
