-- Phase 10 — Dunning attempts table for retry-on-payment-failure

CREATE TABLE dunning_attempts (
    id              BIGSERIAL    PRIMARY KEY,
    subscription_id BIGINT       NOT NULL,
    invoice_id      BIGINT       NOT NULL,
    attempt_number  INT          NOT NULL,
    scheduled_at    TIMESTAMP    NOT NULL,
    status          VARCHAR(16)  NOT NULL
        CHECK (status IN ('SCHEDULED', 'SUCCESS', 'FAILED')),
    last_error      TEXT         NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_dunning_subscription
    ON dunning_attempts (subscription_id);

CREATE INDEX idx_dunning_due
    ON dunning_attempts (scheduled_at, status)
    WHERE status = 'SCHEDULED';
