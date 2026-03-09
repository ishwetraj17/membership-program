-- V37: Add routing_snapshot_json column to payment_attempts
--
-- Phase 5 — Gateway Health and Routing Cache
-- Captures the full routing decision audit trail at the moment a payment attempt
-- is created: which rule matched, which gateway was selected (preferred or fallback),
-- and the observed health status of each gateway candidate.
--
-- The column is TEXT (JSON payload) because:
--   1. Schema of the snapshot is expected to evolve as routing logic grows.
--   2. Queries against the column are not expected to filter or index on its contents
--      in the near term; JSON-path queries can be added later if needed.
--
-- NULL is valid for:
--   - Attempts created before this migration (existing rows).
--   - Attempts where no routing rules matched and the gateway hint from the
--     payment-confirm request was used as a fallback.

ALTER TABLE payment_attempts
    ADD COLUMN IF NOT EXISTS routing_snapshot_json TEXT;
