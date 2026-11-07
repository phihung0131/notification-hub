-- Baseline schema (replaces hibernate ddl-auto=update). Idempotent so it is safe on databases
-- that were previously created by Hibernate (baseline-version=0 + IF NOT EXISTS).

CREATE TABLE IF NOT EXISTS channels (
    id     VARCHAR(255) PRIMARY KEY,
    name   VARCHAR(255) NOT NULL,
    code   VARCHAR(255) NOT NULL UNIQUE,
    active BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS notifications (
    id         UUID         PRIMARY KEY,
    tenant_id  VARCHAR(255) NOT NULL,
    recipient  VARCHAR(255) NOT NULL,
    subject    VARCHAR(255),
    content    TEXT,
    status     VARCHAR(255) NOT NULL,
    channel_id VARCHAR(255) NOT NULL REFERENCES channels (id),
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tenant_id  ON notifications (tenant_id);
CREATE INDEX IF NOT EXISTS idx_status     ON notifications (status);
CREATE INDEX IF NOT EXISTS idx_created_at ON notifications (created_at);
