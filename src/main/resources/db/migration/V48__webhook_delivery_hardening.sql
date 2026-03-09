-- V48__webhook_delivery_hardening.sql
-- Phase 17: Webhook Delivery Hardening
--
-- Adds consecutive-failure tracking + auto-disable timestamp to endpoints,
-- and processing-lease visibility + delivery fingerprint to deliveries.

-- ── merchant_webhook_endpoints ────────────────────────────────────────────────

-- Consecutive-failure counter: incremented on every failed dispatchOne() call,
-- reset to 0 on any successful 2xx response.
ALTER TABLE merchant_webhook_endpoints
    ADD COLUMN IF NOT EXISTS consecutive_failures INT NOT NULL DEFAULT 0;

-- Set when the system auto-disables an endpoint after repeated failures.
-- NULL means the endpoint has never been auto-disabled.
ALTER TABLE merchant_webhook_endpoints
    ADD COLUMN IF NOT EXISTS auto_disabled_at TIMESTAMP NULL;

-- ── merchant_webhook_deliveries ───────────────────────────────────────────────

-- Identity of the pod/process that is currently dispatching this delivery.
-- Pattern: {hostname}:{pid}  (e.g. "pod-abc:12345").
-- Cleared when the delivery reaches a terminal state.
ALTER TABLE merchant_webhook_deliveries
    ADD COLUMN IF NOT EXISTS processing_owner VARCHAR(128) NULL;

-- Timestamp set when dispatch begins and cleared on completion.
-- Together with processing_owner, enables detecting stale in-flight deliveries.
ALTER TABLE merchant_webhook_deliveries
    ADD COLUMN IF NOT EXISTS processing_started_at TIMESTAMP NULL;

-- SHA-256 fingerprint of (endpointId | eventType | payload).
-- Used to skip re-enqueueing a delivery that was already DELIVERED
-- (idempotent enqueue guard).
ALTER TABLE merchant_webhook_deliveries
    ADD COLUMN IF NOT EXISTS delivery_fingerprint VARCHAR(255) NULL;

-- ── Indexes ───────────────────────────────────────────────────────────────────

-- Fast lookup of auto-disabled endpoints for monitoring / admin queries.
CREATE INDEX IF NOT EXISTS idx_mwe_auto_disabled
    ON merchant_webhook_endpoints (auto_disabled_at)
    WHERE auto_disabled_at IS NOT NULL;

-- Fast lookup during idempotent enqueue check (fingerprint + status filter).
CREATE INDEX IF NOT EXISTS idx_mwd_delivery_fingerprint
    ON merchant_webhook_deliveries (delivery_fingerprint)
    WHERE delivery_fingerprint IS NOT NULL;

-- Delivery search by event type (searchDeliveries API).
CREATE INDEX IF NOT EXISTS idx_mwd_event_type
    ON merchant_webhook_deliveries (event_type);

-- Delivery search by HTTP response code.
CREATE INDEX IF NOT EXISTS idx_mwd_response_code
    ON merchant_webhook_deliveries (last_response_code);

-- Delivery search by date range (covering the ORDER BY column).
CREATE INDEX IF NOT EXISTS idx_mwd_created_at
    ON merchant_webhook_deliveries (created_at DESC);
