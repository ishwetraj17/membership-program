-- V28: Merchant outbound webhook endpoints and delivery tracking
-- Phase 14: Outbound Merchant Webhooks

CREATE TABLE merchant_webhook_endpoints (
    id                     BIGSERIAL     PRIMARY KEY,
    merchant_id            BIGINT        NOT NULL REFERENCES merchant_accounts(id),
    url                    TEXT          NOT NULL,
    secret                 VARCHAR(255)  NOT NULL,
    active                 BOOLEAN       NOT NULL DEFAULT TRUE,
    subscribed_events_json TEXT          NOT NULL,
    created_at             TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_mwe_merchant_active ON merchant_webhook_endpoints(merchant_id, active);

CREATE TABLE merchant_webhook_deliveries (
    id                 BIGSERIAL     PRIMARY KEY,
    endpoint_id        BIGINT        NOT NULL REFERENCES merchant_webhook_endpoints(id),
    event_type         VARCHAR(64)   NOT NULL,
    payload            TEXT          NOT NULL,
    signature          VARCHAR(255)  NOT NULL,
    status             VARCHAR(16)   NOT NULL,
    attempt_count      INT           NOT NULL DEFAULT 0,
    last_response_code INT           NULL,
    last_error         TEXT          NULL,
    next_attempt_at    TIMESTAMP     NULL,
    created_at         TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_mwd_status CHECK (status IN ('PENDING','DELIVERED','FAILED','GAVE_UP'))
);

CREATE INDEX idx_mwd_status_next_attempt ON merchant_webhook_deliveries(status, next_attempt_at);
CREATE INDEX idx_mwd_endpoint_id ON merchant_webhook_deliveries(endpoint_id);
