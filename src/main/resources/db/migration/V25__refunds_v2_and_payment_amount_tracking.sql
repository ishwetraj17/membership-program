-- =============================================================================
-- V25 — Refunds V2: partial refund tracking + payment amount columns
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Add amount-tracking columns to payments
-- ---------------------------------------------------------------------------
ALTER TABLE payments
    ADD COLUMN captured_amount  DECIMAL(18,4) NOT NULL DEFAULT 0,
    ADD COLUMN refunded_amount  DECIMAL(18,4) NOT NULL DEFAULT 0,
    ADD COLUMN disputed_amount  DECIMAL(18,4) NOT NULL DEFAULT 0,
    ADD COLUMN net_amount       DECIMAL(18,4) NOT NULL DEFAULT 0,
    ADD COLUMN merchant_id      BIGINT        NULL     REFERENCES merchant_accounts(id);

-- Backfill existing CAPTURED payments: captured_amount = amount, net_amount = amount
UPDATE payments
SET    captured_amount = amount,
       net_amount      = amount
WHERE  status = 'CAPTURED';

-- ---------------------------------------------------------------------------
-- 2. Extend ledger_entries check constraint to include REFUND_V2 reference type
-- ---------------------------------------------------------------------------
-- Drop and re-create the existing constraint (adding REVENUE_RECOGNITION_SCHEDULE
-- which was added to the Java enum in V24 but not to the DB constraint, plus REFUND_V2).
ALTER TABLE ledger_entries DROP CONSTRAINT IF EXISTS ck_ledger_ref_type;
ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_ref_type CHECK (
        reference_type IN (
            'INVOICE', 'PAYMENT', 'REFUND', 'SUBSCRIPTION', 'SETTLEMENT_BATCH',
            'REVENUE_RECOGNITION_SCHEDULE', 'REFUND_V2'
        )
    );

-- ---------------------------------------------------------------------------
-- 3. Create refunds_v2 table
-- ---------------------------------------------------------------------------
CREATE TABLE refunds_v2 (
    id               BIGSERIAL       PRIMARY KEY,
    merchant_id      BIGINT          NOT NULL REFERENCES merchant_accounts(id),
    payment_id       BIGINT          NOT NULL REFERENCES payments(id),
    invoice_id       BIGINT          NULL     REFERENCES invoices(id),
    amount           DECIMAL(18,4)   NOT NULL,
    reason_code      VARCHAR(64)     NOT NULL,
    status           VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    refund_reference VARCHAR(128)    NULL,
    created_at       TIMESTAMP       NOT NULL DEFAULT NOW(),
    completed_at     TIMESTAMP       NULL,

    CONSTRAINT ck_refunds_v2_amount      CHECK (amount > 0),
    CONSTRAINT ck_refunds_v2_status      CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_refunds_v2_payment_id  ON refunds_v2(payment_id);
CREATE INDEX idx_refunds_v2_merchant_id ON refunds_v2(merchant_id);
