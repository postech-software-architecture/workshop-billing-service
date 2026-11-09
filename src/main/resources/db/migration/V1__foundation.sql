CREATE TABLE outbox (
 id UUID PRIMARY KEY, body TEXT NOT NULL, exchange_name TEXT NOT NULL, routing_key TEXT NOT NULL,
 traceparent TEXT, published BOOLEAN NOT NULL DEFAULT FALSE, attempts INTEGER NOT NULL DEFAULT 0,
 next_at TIMESTAMPTZ NOT NULL DEFAULT now(), lease_until TIMESTAMPTZ, lease_token UUID);
CREATE INDEX outbox_pending ON outbox(next_at) WHERE published=false;
CREATE TABLE inbox (message_id UUID PRIMARY KEY, processed_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE foundation_state (id INTEGER PRIMARY KEY CHECK(id=1), schema_version INTEGER NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
INSERT INTO foundation_state(id,schema_version) VALUES(1,1);
