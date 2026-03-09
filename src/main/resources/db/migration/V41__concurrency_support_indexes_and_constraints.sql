-- ============================================================================
-- V41: Concurrency Support — Indexes and Constraints
-- Phase 9: Domain Concurrency Hardening
--
-- Goals:
--   1. Prevent duplicate revenue recognition rows under concurrent generation.
--   2. Provide fast SKIP LOCKED-compatible scan paths for dunning + webhook
--      batch processors.
--   3. Strengthen recon report uniqueness at the DB level (already unique=true
--      on the entity but not always reflected in older DB states).
--   4. Add @Version support column to revenue_recognition_schedules for OCC.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. revenue_recognition_schedules: add optimistic-lock version column
--    and a unique constraint on (invoice_id, recognition_date).
--
--    Rationale: The idempotency check in RevenueRecognitionPostingServiceImpl
--    (status == POSTED) is a TOCTOU race under REQUIRES_NEW isolation.
--    A @Version column gives Hibernate OCC as last-resort guard.
--    The (invoice_id, recognition_date) unique constraint prevents two
--    concurrent generation calls from inserting duplicate schedule rows.
-- ---------------------------------------------------------------------------
ALTER TABLE revenue_recognition_schedules
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE revenue_recognition_schedules
    DROP CONSTRAINT IF EXISTS uq_rrs_invoice_date;

ALTER TABLE revenue_recognition_schedules
    ADD CONSTRAINT uq_rrs_invoice_date
        UNIQUE (invoice_id, recognition_date);

-- Index to make the SKIP LOCKED posting scan fast: date + status
CREATE INDEX IF NOT EXISTS idx_rrs_pending_date
    ON revenue_recognition_schedules (recognition_date, status)
    WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------
-- 2. dunning_attempts: add composite index optimised for the
--    SKIP LOCKED polling query.
--
--    Query pattern:
--      SELECT ... FROM dunning_attempts
--      WHERE dunning_policy_id IS NOT NULL
--        AND status = 'SCHEDULED'
--        AND scheduled_at <= :now
--      ORDER BY scheduled_at
--      FOR UPDATE SKIP LOCKED
--
--    The existing idx_dunning_due covers (scheduled_at, status) but does NOT
--    include the dunning_policy_id IS NOT NULL filter efficiently.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_dunning_v2_due
    ON dunning_attempts (scheduled_at, status)
    WHERE dunning_policy_id IS NOT NULL AND status = 'SCHEDULED';

-- ---------------------------------------------------------------------------
-- 3. merchant_webhook_deliveries: add composite index for the SKIP LOCKED
--    retry scan.
--
--    Query pattern:
--      SELECT ... FROM merchant_webhook_deliveries
--      WHERE status IN ('PENDING', 'FAILED')
--        AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
--      ORDER BY next_attempt_at
--      FOR UPDATE SKIP LOCKED
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_due
    ON merchant_webhook_deliveries (next_attempt_at, status)
    WHERE status IN ('PENDING', 'FAILED');

-- ---------------------------------------------------------------------------
-- 4. recon_reports: ensure the report_date unique constraint exists at DB level.
--
--    The entity already declares unique=true on report_date; this migration
--    makes it explicit in the schema so partial-index scans also benefit.
-- ---------------------------------------------------------------------------
ALTER TABLE recon_reports
    DROP CONSTRAINT IF EXISTS uq_recon_report_date;

ALTER TABLE recon_reports
    ADD CONSTRAINT uq_recon_report_date UNIQUE (report_date);

-- Index to support SELECT FOR UPDATE by report_date
CREATE INDEX IF NOT EXISTS idx_recon_report_date
    ON recon_reports (report_date);

-- ---------------------------------------------------------------------------
-- 5. payment_intents_v2: index on (id, status) to support fast conditional
--    update guard for terminal-state validation on confirm paths.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_pi_v2_id_status
    ON payment_intents_v2 (id, status);
