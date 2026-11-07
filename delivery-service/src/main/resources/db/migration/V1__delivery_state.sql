-- Processed-id store + delivery state machine (rank forbids regression, terminal = rank 2).
CREATE TABLE notification_delivery (
    notification_id     UUID PRIMARY KEY,
    tenant_id           VARCHAR(128),
    channel             VARCHAR(32),
    recipient           VARCHAR(512),
    status              VARCHAR(16)  NOT NULL DEFAULT 'IN_PROGRESS',
    status_rank         SMALLINT     NOT NULL DEFAULT 0,
    result_published    BOOLEAN      NOT NULL DEFAULT FALSE,
    provider_message_id VARCHAR(256),
    failure_reason      TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_delivery_updated_at ON notification_delivery (updated_at);

-- Provider webhook dedupe.
CREATE TABLE provider_event (
    event_id        VARCHAR(128) PRIMARY KEY,
    notification_id UUID         NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Recipient opt-out list.
CREATE TABLE opt_out (
    tenant_id  VARCHAR(128) NOT NULL,
    recipient  VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, recipient)
);
