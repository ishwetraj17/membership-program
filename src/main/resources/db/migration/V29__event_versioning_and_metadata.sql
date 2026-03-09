-- ============================================================
-- V29 — Event versioning and metadata
-- Phase 15: Event Versioning, Domain Event Expansion, and Replay Improvement
-- ============================================================

-- ── domain_events — add versioning + metadata columns ─────────────────────────

ALTER TABLE domain_events
    ADD COLUMN IF NOT EXISTS event_version   INT          NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS schema_version  INT          NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS correlation_id  VARCHAR(128) NULL,
    ADD COLUMN IF NOT EXISTS causation_id    VARCHAR(128) NULL,
    ADD COLUMN IF NOT EXISTS aggregate_type  VARCHAR(64)  NULL,
    ADD COLUMN IF NOT EXISTS aggregate_id    VARCHAR(128) NULL,
    ADD COLUMN IF NOT EXISTS merchant_id     BIGINT       NULL;

-- ── outbox_events — add versioning + metadata columns ─────────────────────────

ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS event_version   INT          NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS schema_version  INT          NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS correlation_id  VARCHAR(128) NULL,
    ADD COLUMN IF NOT EXISTS causation_id    VARCHAR(128) NULL,
    ADD COLUMN IF NOT EXISTS aggregate_type  VARCHAR(64)  NULL,
    ADD COLUMN IF NOT EXISTS aggregate_id    VARCHAR(128) NULL,
    ADD COLUMN IF NOT EXISTS merchant_id     BIGINT       NULL;

-- ── indexes ───────────────────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_domain_events_merchant_created
    ON domain_events(merchant_id, created_at);

CREATE INDEX IF NOT EXISTS idx_domain_events_aggregate
    ON domain_events(aggregate_type, aggregate_id);

CREATE INDEX IF NOT EXISTS idx_outbox_events_merchant_status
    ON outbox_events(merchant_id, status);
