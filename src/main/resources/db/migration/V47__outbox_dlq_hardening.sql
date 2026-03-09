-- Phase 16: Outbox and DLQ Hardening
-- ═══════════════════════════════════════════════════════════════════════════
-- Purpose
--   Adds processing lease visibility, failure categorisation, and per-merchant
--   DLQ filtering to support Phase 16 operational improvements.
--
-- outbox_events additions
--   processing_started_at  — timestamp when this event entered PROCESSING state
--   processing_owner       — hostname:pid of the JVM that claimed the lease
--   handler_fingerprint    — optional dedup key set by the event handler
--   failure_category       — coarse category of the last failure
--
-- dead_letter_messages additions
--   failure_category  — mirrors the outbox failure category at time of DLQ write
--   merchant_id       — copied from the corresponding outbox event for filtering
-- ═══════════════════════════════════════════════════════════════════════════

-- ── outbox_events: processing lease columns ───────────────────────────────

ALTER TABLE outbox_events
    ADD COLUMN processing_started_at TIMESTAMP NULL;

ALTER TABLE outbox_events
    ADD COLUMN processing_owner VARCHAR(128) NULL;

ALTER TABLE outbox_events
    ADD COLUMN handler_fingerprint VARCHAR(255) NULL;

ALTER TABLE outbox_events
    ADD COLUMN failure_category VARCHAR(64) NULL;

-- Index used by stale-lease recovery to find PROCESSING rows stuck > threshold
CREATE INDEX idx_outbox_stale_lease
    ON outbox_events(status, processing_started_at)
    WHERE status = 'PROCESSING';

-- Index to support oldest-pending-age reporting (new + processing events)
CREATE INDEX idx_outbox_created_at_pending
    ON outbox_events(created_at)
    WHERE status IN ('NEW', 'PROCESSING');

-- ── dead_letter_messages: categorisation + merchant visibility ────────────

ALTER TABLE dead_letter_messages
    ADD COLUMN failure_category VARCHAR(64) NULL;

ALTER TABLE dead_letter_messages
    ADD COLUMN merchant_id BIGINT NULL;

-- Index for filtering DLQ by source (OUTBOX / WEBHOOK)
CREATE INDEX idx_dlq_source
    ON dead_letter_messages(source);

-- Index for filtering DLQ by failure category
CREATE INDEX idx_dlq_failure_category
    ON dead_letter_messages(failure_category)
    WHERE failure_category IS NOT NULL;

-- Index for filtering DLQ by merchant
CREATE INDEX idx_dlq_merchant_id
    ON dead_letter_messages(merchant_id)
    WHERE merchant_id IS NOT NULL;
