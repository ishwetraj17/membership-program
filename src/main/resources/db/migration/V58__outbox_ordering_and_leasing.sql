-- ============================================================
-- Phase 12: outbox per-aggregate ordering and lease-based heartbeat recovery
-- ============================================================
-- Note: aggregate_type, aggregate_id, schema_version, event_version were added
-- in V29; processing_started_at and processing_owner were added in V47.
-- This migration adds the missing aggregate_sequence and lease_expires_at columns.

ALTER TABLE outbox_events
    ADD COLUMN aggregate_sequence BIGINT    NULL,
    ADD COLUMN lease_expires_at   TIMESTAMP NULL;

-- Partial index for the priority poller: fresh events (attempts = 0) only
CREATE INDEX idx_outbox_fresh_events
    ON outbox_events (created_at ASC)
    WHERE status = 'NEW' AND attempts = 0;

-- Partial index for retry events: due retries only
CREATE INDEX idx_outbox_retry_events
    ON outbox_events (next_attempt_at ASC)
    WHERE status = 'NEW' AND attempts > 0;

-- Per-aggregate ordering: allows consumers to read events in sequence order
CREATE INDEX idx_outbox_aggregate_order
    ON outbox_events (aggregate_type, aggregate_id, aggregate_sequence)
    WHERE aggregate_type IS NOT NULL AND aggregate_sequence IS NOT NULL;

-- Stale lease recovery: find PROCESSING events with expired leases efficiently
CREATE INDEX idx_outbox_lease_expiry
    ON outbox_events (lease_expires_at ASC)
    WHERE status = 'PROCESSING' AND lease_expires_at IS NOT NULL;
