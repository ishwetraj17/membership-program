-- Phase 14: Reconciliation mismatch taxonomy, expectation classification, and FX fields
-- V60 (spec requested V45, which is already taken by V45__revenue_hardening.sql)

-- ── recon_mismatches: expectation / severity / gateway anchor / FX identity ─

ALTER TABLE recon_mismatches
    ADD COLUMN expectation           VARCHAR(40)     NULL,
    ADD COLUMN severity              VARCHAR(16)     NULL,
    ADD COLUMN gateway_transaction_id VARCHAR(128)   NULL,
    ADD COLUMN merchant_id           BIGINT          NULL,
    ADD COLUMN currency              VARCHAR(10)     NULL,
    ADD COLUMN settlement_currency   VARCHAR(3)      NULL,
    ADD COLUMN fx_rate               DECIMAL(18, 8)  NULL;

-- Index: look up critical open mismatches quickly
CREATE INDEX idx_recon_mismatches_severity_status
    ON recon_mismatches(severity, status)
    WHERE severity IS NOT NULL;

-- Index: look up by gateway txn ID for orphan detection
CREATE INDEX idx_recon_mismatches_gateway_txn
    ON recon_mismatches(gateway_transaction_id)
    WHERE gateway_transaction_id IS NOT NULL;

-- ── payment_intents_v2: settlement / FX fields ───────────────────────────────

ALTER TABLE payment_intents_v2
    ADD COLUMN settlement_currency     VARCHAR(3)     NULL,
    ADD COLUMN settlement_amount_minor BIGINT         NULL,
    ADD COLUMN fx_rate                 DECIMAL(18, 8) NULL,
    ADD COLUMN fx_rate_captured_at     TIMESTAMP      NULL;

COMMENT ON COLUMN payment_intents_v2.settlement_currency IS
    'ISO-4217 currency code in which the merchant will receive settlement (may differ from charge currency)';
COMMENT ON COLUMN payment_intents_v2.settlement_amount_minor IS
    'Amount in settlement_currency, in the smallest denomination (paisa / cents)';
COMMENT ON COLUMN payment_intents_v2.fx_rate IS
    'Exchange rate applied: 1 unit of currency = fx_rate units of settlement_currency';
COMMENT ON COLUMN payment_intents_v2.fx_rate_captured_at IS
    'Timestamp at which the FX rate was locked by the gateway';
