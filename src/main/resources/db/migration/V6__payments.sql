-- =============================================================================
-- V6 — Payments module: PaymentIntents, Payments, WebhookEvents, DeadLetter
-- Note: user requested V3 but V3-V5 are taken; this is the next free version.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. payment_intents
-- ---------------------------------------------------------------------------
CREATE TABLE payment_intents (
    id               BIGSERIAL        PRIMARY KEY,
    invoice_id       BIGINT           NULL,
    amount           DECIMAL(10,2)    NOT NULL,
    currency         VARCHAR(10)      NOT NULL DEFAULT 'INR',
    status           VARCHAR(40)      NOT NULL,
    client_secret    VARCHAR(64)      NOT NULL UNIQUE,
    gateway_reference VARCHAR(64)     NOT NULL UNIQUE,
    created_at       TIMESTAMP        NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP        NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pi_status      ON payment_intents(status);
CREATE INDEX idx_pi_invoice_id  ON payment_intents(invoice_id);

-- ---------------------------------------------------------------------------
-- 2. payments  (one row per charge attempt on a PaymentIntent)
-- ---------------------------------------------------------------------------
CREATE TABLE payments (
    id                 BIGSERIAL        PRIMARY KEY,
    payment_intent_id  BIGINT           NOT NULL REFERENCES payment_intents(id),
    amount             DECIMAL(10,2)    NOT NULL,
    currency           VARCHAR(10)      NOT NULL,
    status             VARCHAR(40)      NOT NULL,   -- CAPTURED | FAILED
    gateway_txn_id     VARCHAR(64)      NOT NULL UNIQUE,
    captured_at        TIMESTAMP        NULL,
    created_at         TIMESTAMP        NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payments_intent_id ON payments(payment_intent_id);

-- ---------------------------------------------------------------------------
-- 3. webhook_events  (idempotent log; includes retry bookkeeping columns)
-- ---------------------------------------------------------------------------
CREATE TABLE webhook_events (
    id               BIGSERIAL     PRIMARY KEY,
    event_id         VARCHAR(64)   NOT NULL UNIQUE,
    event_type       VARCHAR(64)   NOT NULL,
    payload          TEXT          NOT NULL,
    signature_valid  BOOLEAN       NOT NULL,
    processed        BOOLEAN       NOT NULL DEFAULT FALSE,
    attempts         INT           NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMP     NULL,
    created_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    processed_at     TIMESTAMP     NULL,
    last_error       TEXT          NULL
);

CREATE INDEX idx_we_processed    ON webhook_events(processed);
CREATE INDEX idx_we_next_attempt ON webhook_events(next_attempt_at);

-- ---------------------------------------------------------------------------
-- 4. dead_letter_messages
-- ---------------------------------------------------------------------------
CREATE TABLE dead_letter_messages (
    id         BIGSERIAL   PRIMARY KEY,
    source     VARCHAR(32) NOT NULL,   -- WEBHOOK | OUTBOX
    payload    TEXT        NOT NULL,
    error      TEXT        NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT NOW()
);
