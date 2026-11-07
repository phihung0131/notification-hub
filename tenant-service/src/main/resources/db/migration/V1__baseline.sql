-- Baseline schema (replaces hibernate ddl-auto=update). Idempotent so it is safe on databases
-- that were previously created by Hibernate (baseline-version=0 + IF NOT EXISTS).

CREATE TABLE IF NOT EXISTS tenants (
    id           VARCHAR(255) PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    plan         VARCHAR(255) NOT NULL,
    status       VARCHAR(255) NOT NULL,
    email        VARCHAR(255) NOT NULL UNIQUE,
    password     VARCHAR(255) NOT NULL,
    quota_limit  INTEGER      NOT NULL DEFAULT 0,
    quota_used   INTEGER      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);

CREATE TABLE IF NOT EXISTS permission (
    id          VARCHAR(255) PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL,
    type        VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    CONSTRAINT uk_permission_name_type UNIQUE (name, type)
);

CREATE TABLE IF NOT EXISTS tenant_permissions (
    tenant_id     VARCHAR(255) NOT NULL REFERENCES tenants (id),
    permission_id VARCHAR(255) NOT NULL REFERENCES permission (id),
    PRIMARY KEY (tenant_id, permission_id)
);

CREATE TABLE IF NOT EXISTS api_key (
    id            VARCHAR(255) PRIMARY KEY,
    api_key_value VARCHAR(255) NOT NULL UNIQUE,
    tenant_id     VARCHAR(255) NOT NULL REFERENCES tenants (id),
    revoked       BOOLEAN      NOT NULL DEFAULT FALSE,
    expired_at    TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS apikey_permissions (
    apikey_id     VARCHAR(255) NOT NULL REFERENCES api_key (id),
    permission_id VARCHAR(255) NOT NULL REFERENCES permission (id),
    PRIMARY KEY (apikey_id, permission_id)
);

CREATE TABLE IF NOT EXISTS processed_events (
    id           VARCHAR(255) PRIMARY KEY,
    message_id   VARCHAR(255) NOT NULL,
    tenant_id    VARCHAR(255) NOT NULL,
    event_type   VARCHAR(255) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_processed_events_message_id UNIQUE (message_id)
);
CREATE INDEX IF NOT EXISTS idx_message_id   ON processed_events (message_id);
CREATE INDEX IF NOT EXISTS idx_processed_at ON processed_events (processed_at);
