-- Idempotency: final arbiter is the unique (tenant_id, idempotency_key) index.
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS idempotency_key     VARCHAR(255);
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS request_fingerprint VARCHAR(64);
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS priority            VARCHAR(16) NOT NULL DEFAULT 'NORMAL';

-- Partial: rows without a key (or after the 24h cleanup nulls it) are not constrained.
CREATE UNIQUE INDEX IF NOT EXISTS uk_notifications_tenant_idempotency
    ON notifications (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_notifications_tenant_created ON notifications (tenant_id, created_at);

-- Transactional outbox (write-mode=outbox). Written in the same transaction as the notification.
CREATE TABLE IF NOT EXISTS outbox_events (
    id           UUID         PRIMARY KEY,
    aggregate_id UUID         NOT NULL,
    tenant_id    VARCHAR(255) NOT NULL,
    topic        VARCHAR(255) NOT NULL,
    event_key    VARCHAR(255) NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    sent_at      TIMESTAMPTZ
);
-- relay scan: only unsent rows, oldest first
CREATE INDEX IF NOT EXISTS idx_outbox_unsent ON outbox_events (created_at, id) WHERE sent_at IS NULL;

-- Per-tenant idempotency policy. No row = global default (HEADER_ONLY).
CREATE TABLE IF NOT EXISTS tenant_idempotency_config (
    tenant_id           VARCHAR(255) PRIMARY KEY,
    mode                VARCHAR(32)  NOT NULL DEFAULT 'HEADER_ONLY', -- HEADER_ONLY | HEADER_FIRST
    hash_window_seconds INTEGER      NOT NULL DEFAULT 60
);
