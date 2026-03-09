-- V20: Payment Intents V2 and Payment Attempts
-- Introduces attempt-level tracking and orchestration core.
-- The legacy `payment_intents` table is preserved untouched.

-- ─────────────────────────────────────────────────────────────────────────────
-- Table: payment_intents_v2
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE payment_intents_v2 (
    id               BIGSERIAL PRIMARY KEY,
    merchant_id      BIGINT          NOT NULL REFERENCES merchant_accounts(id),
    customer_id      BIGINT          NOT NULL REFERENCES customers(id),
    invoice_id       BIGINT          NULL     REFERENCES invoices(id),
    subscription_id  BIGINT          NULL     REFERENCES subscriptions_v2(id),
    amount           DECIMAL(18,4)   NOT NULL,
    currency         VARCHAR(10)     NOT NULL,
    status           VARCHAR(32)     NOT NULL DEFAULT 'REQUIRES_PAYMENT_METHOD',
    payment_method_id BIGINT         NULL     REFERENCES payment_methods(id),
    capture_mode     VARCHAR(16)     NOT NULL DEFAULT 'AUTO',
    client_secret    VARCHAR(128)    NOT NULL UNIQUE,
    idempotency_key  VARCHAR(128)    NULL,
    metadata_json    TEXT            NULL,
    version          BIGINT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Table: payment_attempts
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE payment_attempts (
    id                  BIGSERIAL PRIMARY KEY,
    payment_intent_id   BIGINT          NOT NULL REFERENCES payment_intents_v2(id),
    attempt_number      INT             NOT NULL,
    gateway_name        VARCHAR(64)     NOT NULL,
    gateway_reference   VARCHAR(128)    NULL,
    request_hash        VARCHAR(128)    NULL,
    response_code       VARCHAR(64)     NULL,
    response_message    TEXT            NULL,
    latency_ms          BIGINT          NULL,
    status              VARCHAR(32)     NOT NULL DEFAULT 'STARTED',
    failure_category    VARCHAR(32)     NULL,
    retriable           BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMP       NULL,
    UNIQUE (payment_intent_id, attempt_number)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Indexes
-- ─────────────────────────────────────────────────────────────────────────────
CREATE INDEX idx_pi_v2_merchant_customer_status
    ON payment_intents_v2 (merchant_id, customer_id, status);

CREATE INDEX idx_pi_v2_invoice
    ON payment_intents_v2 (invoice_id);

CREATE INDEX idx_pi_v2_status_created
    ON payment_intents_v2 (status, created_at);

CREATE INDEX idx_payment_attempts_pi_id
    ON payment_attempts (payment_intent_id);

CREATE INDEX idx_payment_attempts_gateway_ref
    ON payment_attempts (gateway_name, gateway_reference);
