-- ─────────────────────────────────────────────────────────────
-- V3: Performance indexes for scheduler queries
-- ─────────────────────────────────────────────────────────────

-- Supports findSubscriptionsForRenewal:
--   WHERE status='ACTIVE' AND autoRenewal=true AND nextBillingDate <= ?
CREATE INDEX idx_subscriptions_next_billing
    ON subscriptions(next_billing_date)
    WHERE status = 'ACTIVE'
    AND auto_renewal = true;

-- Supports bulkExpireSubscriptions:
--   WHERE status='ACTIVE' AND endDate < ?
CREATE INDEX idx_subscriptions_status_end_date
    ON subscriptions(status, end_date);
