-- Phase 9 — Subscription lifecycle fields for managed renewal
-- Adds fields used by the RenewalService and DunningService.

ALTER TABLE subscriptions
    ADD COLUMN cancel_at_period_end BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN grace_until          TIMESTAMP    NULL,
    ADD COLUMN paused_until         TIMESTAMP    NULL,
    ADD COLUMN next_renewal_at      TIMESTAMP    NULL;

-- Efficient lookup for the renewal scheduler
CREATE INDEX idx_subscription_renewal
    ON subscriptions (next_renewal_at, status)
    WHERE status = 'ACTIVE';
