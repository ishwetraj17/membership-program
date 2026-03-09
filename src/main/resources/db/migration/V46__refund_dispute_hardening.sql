-- ============================================================
-- Phase 15 – Refund & Dispute Robustness Hardening
-- ============================================================
-- refunds_v2 : add request_fingerprint for idempotency
-- disputes   : add reserve_posted / resolution_posted guards
-- ============================================================

-- ── refunds_v2 ──────────────────────────────────────────────────────────────

-- SHA-256 fingerprint stored with every refund so that duplicate requests
-- (same merchant + payment + amount + reasonCode) are returned idempotently
-- rather than creating a second refund.  Callers may provide their own value;
-- the service generates one when absent.
ALTER TABLE refunds_v2
    ADD COLUMN request_fingerprint VARCHAR(255) NULL;

-- Partial unique index (only non-NULL rows are indexed, keeping old rows clean).
CREATE UNIQUE INDEX idx_refunds_v2_fingerprint
    ON refunds_v2 (request_fingerprint)
    WHERE request_fingerprint IS NOT NULL;

-- ── disputes ────────────────────────────────────────────────────────────────

-- One-time posting guards: set to TRUE after the accounting entry is written.
-- Prevents double-DR with the DISPUTE_RESERVE account on retries or repair runs.
ALTER TABLE disputes
    ADD COLUMN reserve_posted    BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE disputes
    ADD COLUMN resolution_posted BOOLEAN NOT NULL DEFAULT FALSE;

-- Index to support efficient due-soon queries
-- (DisputeDueDateCheckerService / GET /api/v2/admin/disputes/due-soon).
CREATE INDEX idx_disputes_due_by
    ON disputes (due_by)
    WHERE due_by IS NOT NULL;
