-- V66: Phase 20 — Ops timeline hardening and support case field additions.
--
-- The tables (support_cases, support_notes, ops_timeline_events) already exist
-- from V49 and V43 respectively.  This migration adds the columns specified in
-- the Phase 20 design that were not yet present.
--
-- support_cases: customer_id, created_by, assigned_to
-- ops_timeline_events: request_id

-- ── support_cases additions ─────────────────────────────────────────────────

ALTER TABLE support_cases
    ADD COLUMN IF NOT EXISTS customer_id  BIGINT       NULL,
    ADD COLUMN IF NOT EXISTS created_by   VARCHAR(120) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS assigned_to  BIGINT       NULL;

-- Index for customer-scoped lookups (e.g. "all open cases for customer X")
CREATE INDEX IF NOT EXISTS idx_sc_customer_id
    ON support_cases (customer_id)
    WHERE customer_id IS NOT NULL;

-- Index for agent workload view ("show me my assigned cases")
CREATE INDEX IF NOT EXISTS idx_sc_assigned_to
    ON support_cases (assigned_to)
    WHERE assigned_to IS NOT NULL;

-- ── ops_timeline_events additions ───────────────────────────────────────────

ALTER TABLE ops_timeline_events
    ADD COLUMN IF NOT EXISTS request_id  VARCHAR(128) NULL;

CREATE INDEX IF NOT EXISTS idx_timeline_request_id
    ON ops_timeline_events (request_id)
    WHERE request_id IS NOT NULL;
