-- ============================================================================
-- V45: Revenue Recognition Hardening (Phase 14)
-- ============================================================================
-- Changes:
--   1. generation_fingerprint — SHA-256 of the inputs used to generate a
--      schedule set (invoiceId:subscriptionId:grandTotal:periodStart:periodEnd).
--      Same fingerprint → same generation inputs → idempotency / audit record.
--   2. posting_run_id — identifies which postDueRecognitionsForDate() batch
--      invocation posted a schedule row.  Allows operators to query "all rows
--      posted in run X" for incident response.
--   3. catch_up_run — TRUE when rows were generated via an explicit repair /
--      force-regeneration path rather than the normal invoice-payment trigger.
--   4. revenue_waterfall_projection — one row per merchant per business day,
--      containing billed / deferred / recognized / refunded / disputed totals.
-- ============================================================================

-- 1. generation_fingerprint
ALTER TABLE revenue_recognition_schedules
    ADD COLUMN generation_fingerprint VARCHAR(255) NULL;

-- 2. posting_run_id
ALTER TABLE revenue_recognition_schedules
    ADD COLUMN posting_run_id BIGINT NULL;

-- 3. catch_up_run
ALTER TABLE revenue_recognition_schedules
    ADD COLUMN catch_up_run BOOLEAN NOT NULL DEFAULT FALSE;

-- Fast lookup by fingerprint (idempotency + audit)
CREATE INDEX idx_rrs_fingerprint
    ON revenue_recognition_schedules(generation_fingerprint)
    WHERE generation_fingerprint IS NOT NULL;

-- Fast lookup by posting run (incident investigation)
CREATE INDEX idx_rrs_posting_run
    ON revenue_recognition_schedules(posting_run_id)
    WHERE posting_run_id IS NOT NULL;

-- ============================================================================
-- 4. Revenue waterfall projection
-- ============================================================================
-- One row per (merchant_id, business_date).
-- Updated by RevenueWaterfallProjectionService after each recognition run.
-- Columns:
--   billed_amount    — invoices finalised (PAID) on this date for this merchant
--   deferred_opening — deferred revenue balance at the start of the day
--   deferred_closing — deferred_opening + billed - recognised - refunded - disputed
--   recognized_amount — schedule rows POSTED on this date
--   refunded_amount   — refunds applied on this date (reserved, default 0)
--   disputed_amount   — disputes opened on this date (reserved, default 0)
-- ============================================================================
CREATE TABLE revenue_waterfall_projection (
    id                  BIGSERIAL     PRIMARY KEY,
    merchant_id         BIGINT        NOT NULL,
    business_date       DATE          NOT NULL,
    billed_amount       NUMERIC(18,4) NOT NULL DEFAULT 0,
    deferred_opening    NUMERIC(18,4) NOT NULL DEFAULT 0,
    deferred_closing    NUMERIC(18,4) NOT NULL DEFAULT 0,
    recognized_amount   NUMERIC(18,4) NOT NULL DEFAULT 0,
    refunded_amount     NUMERIC(18,4) NOT NULL DEFAULT 0,
    disputed_amount     NUMERIC(18,4) NOT NULL DEFAULT 0,
    updated_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_waterfall_merchant_date UNIQUE (merchant_id, business_date)
);

CREATE INDEX idx_waterfall_merchant_date
    ON revenue_waterfall_projection(merchant_id, business_date);
