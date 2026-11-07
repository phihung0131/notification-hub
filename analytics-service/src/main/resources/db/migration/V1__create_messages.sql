-- status_rank mirrors MessageStatusProgression in the domain layer; the upsert WHERE guard
-- compares ranks in SQL so out-of-order or duplicate events can never regress a status.
CREATE TABLE IF NOT EXISTS messages (
    message_id   UUID PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    channel      VARCHAR(32),
    recipient    VARCHAR(320),
    subject      TEXT,
    content      TEXT,
    status       VARCHAR(20)  NOT NULL,
    status_rank  SMALLINT     NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_messages_tenant_created
    ON messages (tenant_id, created_at DESC, message_id DESC);
CREATE INDEX IF NOT EXISTS idx_messages_tenant_status
    ON messages (tenant_id, status);
