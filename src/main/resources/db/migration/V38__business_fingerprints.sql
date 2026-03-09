-- V38: Business-effect fingerprint dedup table for Phase 6
--
-- Phase 6 — Strong Deduplication Layer for Webhooks and Business Events
--
-- This table provides DURABLE, cross-restart deduplication for critical
-- business effects that must occur exactly once.  It operates as the DB layer
-- in a two-tier dedup strategy:
--   Tier 1 (fast path):  Redis SET-NX with domain TTL  (~sub-millisecond)
--   Tier 2 (durable):    INSERT with UNIQUE constraint  (survives Redis restart)
--
-- Use for effects that are financially consequential and must not be applied
-- twice even if Redis is unavailable:
--   PAYMENT_CAPTURE_SUCCESS   — gateway capture confirmed, ledger entry posted
--   REFUND_COMPLETED          — refund issued, money leaving platform
--   DISPUTE_OPENED            — funds reserved, dispute record created
--   SETTLEMENT_BATCH_CREATED  — funds swept to bank ledger
--   REVENUE_RECOGNITION_POSTED — revenue recognized for a schedule row
--
-- Fingerprint computation (SHA-256 of business-unique key fields) is done
-- in BusinessFingerprintService.java and must be deterministic for the same
-- logical event regardless of retry/replay order.
--
-- Webhook-level dedup (provider event_id) still lives in webhook_events.event_id.
-- This table deduplicates the _effect_ of the webhook, not the webhook itself.

CREATE TABLE IF NOT EXISTS business_effect_fingerprints (
    id             BIGSERIAL PRIMARY KEY,
    effect_type    VARCHAR(64)  NOT NULL,
    fingerprint    VARCHAR(128) NOT NULL,
    reference_type VARCHAR(64),
    reference_id   BIGINT,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_effect_fingerprint UNIQUE (effect_type, fingerprint)
);

CREATE INDEX IF NOT EXISTS idx_bef_effect_type
    ON business_effect_fingerprints (effect_type);

CREATE INDEX IF NOT EXISTS idx_bef_created_at
    ON business_effect_fingerprints (created_at);

-- Add provider column to webhook_events so we can scope dedup keys.
-- Nullable with default 'gateway' for back-compat with existing rows.
ALTER TABLE webhook_events
    ADD COLUMN IF NOT EXISTS provider VARCHAR(32) NOT NULL DEFAULT 'gateway';

-- Index to speed up WebhookDedupService lookups
CREATE INDEX IF NOT EXISTS idx_we_provider_event_id
    ON webhook_events (provider, event_id);
