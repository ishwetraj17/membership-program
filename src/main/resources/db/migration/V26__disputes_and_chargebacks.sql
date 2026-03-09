-- =============================================================================
-- V26 — Disputes and Chargebacks
-- =============================================================================

-- 1. Extend ledger check constraints to accept new entry types and reference types
--    (drop and recreate, same pattern as V25)

ALTER TABLE ledger_entries DROP CONSTRAINT IF EXISTS ck_ledger_entry_type;
ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_entry_type CHECK (
        entry_type IN (
            'PAYMENT_CAPTURED',
            'REFUND_ISSUED',
            'REVENUE_RECOGNIZED',
            'SETTLEMENT',
            'DISPUTE_OPENED',
            'DISPUTE_WON',
            'CHARGEBACK_POSTED'
        )
    );

ALTER TABLE ledger_entries DROP CONSTRAINT IF EXISTS ck_ledger_ref_type;
ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_ref_type CHECK (
        reference_type IN (
            'INVOICE', 'PAYMENT', 'REFUND', 'SUBSCRIPTION', 'SETTLEMENT_BATCH',
            'REVENUE_RECOGNITION_SCHEDULE', 'REFUND_V2', 'DISPUTE'
        )
    );

-- 2. Seed new ledger accounts (idempotent — ON CONFLICT DO NOTHING)
INSERT INTO ledger_accounts (name, account_type, currency)
VALUES
    ('DISPUTE_RESERVE',    'ASSET',   'INR'),
    ('CHARGEBACK_EXPENSE', 'EXPENSE', 'INR')
ON CONFLICT (name) DO NOTHING;

-- 3. Create disputes table
CREATE TABLE disputes (
    id          BIGSERIAL     PRIMARY KEY,
    merchant_id BIGINT        NOT NULL REFERENCES merchant_accounts(id),
    payment_id  BIGINT        NOT NULL REFERENCES payments(id),
    customer_id BIGINT        NOT NULL REFERENCES customers(id),
    amount      DECIMAL(18,4) NOT NULL,
    reason_code VARCHAR(64)   NOT NULL,
    status      VARCHAR(32)   NOT NULL DEFAULT 'OPEN',
    opened_at   TIMESTAMP     NOT NULL DEFAULT NOW(),
    due_by      TIMESTAMP     NULL,
    resolved_at TIMESTAMP     NULL,

    CONSTRAINT ck_disputes_amount CHECK (amount > 0),
    CONSTRAINT ck_disputes_status CHECK (
        status IN ('OPEN', 'UNDER_REVIEW', 'WON', 'LOST', 'CLOSED')
    )
);

CREATE INDEX idx_disputes_merchant_status ON disputes(merchant_id, status);
CREATE INDEX idx_disputes_payment_id      ON disputes(payment_id);

-- 4. Create dispute_evidence table
CREATE TABLE dispute_evidence (
    id                BIGSERIAL   PRIMARY KEY,
    dispute_id        BIGINT      NOT NULL REFERENCES disputes(id),
    evidence_type     VARCHAR(64) NOT NULL,
    content_reference TEXT        NOT NULL,
    uploaded_by       BIGINT      NOT NULL REFERENCES users(id),
    created_at        TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_dispute_evidence_dispute_id ON dispute_evidence(dispute_id);
