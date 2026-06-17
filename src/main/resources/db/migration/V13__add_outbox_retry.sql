-- ─────────────────────────────────────────────────────────────
-- V13: Outbox retry tracking (for retry/dead-letter handling).
-- ─────────────────────────────────────────────────────────────

ALTER TABLE outbox_events
    ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;
