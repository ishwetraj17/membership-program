-- ─────────────────────────────────────────────────────────────────────────────
-- V54: Payment attempt model hardening and UNKNOWN outcome path
--
-- Adds gateway idempotency key, transaction ID, payload capture, processor node
-- tracking, and a started_at timestamp to payment_attempts, plus reconciliation
-- state and last-success linkage on payment_intents_v2.
-- ─────────────────────────────────────────────────────────────────────────────

-- ─────────────────────────────────────────────────────────────────────────────
-- Alter payment_attempts: Phase 8 hardening columns
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE payment_attempts
    ADD COLUMN gateway_idempotency_key VARCHAR(200)  NULL,
    ADD COLUMN gateway_transaction_id  VARCHAR(128)  NULL,
    ADD COLUMN request_payload_hash    VARCHAR(128)  NULL,
    ADD COLUMN response_payload_json   TEXT          NULL,
    ADD COLUMN processor_node_id       VARCHAR(255)  NULL,
    ADD COLUMN started_at              TIMESTAMP     NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- Unique partial index: one idempotency key per attempt (NULLs excluded)
-- Prevents duplicate gateway submissions for the same (intent, attempt-number).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE UNIQUE INDEX uq_payment_attempts_gateway_idem_key
    ON payment_attempts (gateway_idempotency_key)
    WHERE gateway_idempotency_key IS NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- Composite index for fast UNKNOWN / timeout recovery queries
-- (status = 'UNKNOWN', started_at < NOW() - interval)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE INDEX idx_payment_attempts_status_started_at
    ON payment_attempts (status, started_at);

-- ─────────────────────────────────────────────────────────────────────────────
-- Alter payment_intents_v2: reconciliation state and last-success linkage
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE payment_intents_v2
    ADD COLUMN last_successful_attempt_id BIGINT      NULL REFERENCES payment_attempts(id),
    ADD COLUMN reconciliation_state       VARCHAR(32) NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- Reference: payment_attempts.status lifecycle after Phase 8
--
--  INITIATED       – attempt object created; idempotency key assigned
--  STARTED         – (legacy) gateway request dispatched
--  AUTHORIZED      – gateway pre-authorised (MANUAL capture mode)
--  CAPTURED        – (legacy) terminal success
--  SUCCEEDED       – Phase 8 terminal success (replaces CAPTURED for new flows)
--  FAILED          – terminal gateway decline
--  TIMEOUT         – (legacy) gateway did not respond; reconcile same as UNKNOWN
--  UNKNOWN         – gateway did not respond; async status check pending
--  RECONCILED      – UNKNOWN resolved via async gateway status check
--  REQUIRES_ACTION – 3-D Secure or additional customer action needed
--  CANCELLED       – attempt cancelled before completion
--
-- reconciliation_state on payment_intents_v2:
--  PENDING         – at least one UNKNOWN attempt; awaiting async resolution
--  RESOLVED        – all UNKNOWN attempts resolved
-- ─────────────────────────────────────────────────────────────────────────────
