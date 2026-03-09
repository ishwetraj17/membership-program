-- Phase 13: Domain event schema versioning and replay safety
-- Adds replay-tracking columns to domain_events.
--
-- Note: schema_version and event_version were already added in V29.
--       This migration adds the replay audit columns only.

ALTER TABLE domain_events
    ADD COLUMN IF NOT EXISTS replayed          BOOLEAN   NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS replayed_at       TIMESTAMP NULL,
    ADD COLUMN IF NOT EXISTS original_event_id BIGINT    NULL;

-- Fast lookup of all replay events for a given original event
CREATE INDEX IF NOT EXISTS idx_domain_events_original_event
    ON domain_events(original_event_id)
    WHERE original_event_id IS NOT NULL;

-- Allow filtering / auditing: "list all events that are replays"
CREATE INDEX IF NOT EXISTS idx_domain_events_replayed_at
    ON domain_events(replayed_at)
    WHERE replayed = TRUE;
