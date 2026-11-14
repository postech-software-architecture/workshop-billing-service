CREATE TABLE payment_context (
 payment_id UUID PRIMARY KEY REFERENCES payments(id), envelope TEXT NOT NULL,
 budget_version INTEGER NOT NULL, qr_data TEXT, cancellation_confirmed BOOLEAN NOT NULL DEFAULT false);
ALTER TABLE financial_operations ADD COLUMN context TEXT;
CREATE TABLE financial_tombstones (
 payment_id UUID PRIMARY KEY, os_id UUID NOT NULL, attempt_id UUID NOT NULL, saga_id UUID NOT NULL,
 command TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE payment_audit (
 id UUID PRIMARY KEY, payment_id UUID NOT NULL REFERENCES payments(id), previous_state TEXT NOT NULL,
 next_state TEXT NOT NULL, version BIGINT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(payment_id,version));
UPDATE foundation_state SET schema_version=6;
