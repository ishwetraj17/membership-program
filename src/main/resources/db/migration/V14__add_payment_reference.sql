-- ─────────────────────────────────────────────────────────────
-- V14: Payment provider reference on charging events.
-- ─────────────────────────────────────────────────────────────

ALTER TABLE subscription_events
    ADD COLUMN payment_reference VARCHAR(100);
