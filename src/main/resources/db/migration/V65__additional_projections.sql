-- Phase 19: Projection hardening — three additional denormalized read-model tables
-- These are NOT sources of truth; they can be fully rebuilt by ProjectionRebuildService.

-- ── 1. Customer payment summary ─────────────────────────────────────────────
-- Per-(merchant, customer) rolling payment counters using minor-unit integers.
-- Distinct from customer_billing_summary_projection (which tracks subscription
-- state / BigDecimal amounts); this projection focuses on raw payment outcomes.
CREATE TABLE IF NOT EXISTS customer_payment_summary_projection (
    merchant_id           BIGINT  NOT NULL,
    customer_id           BIGINT  NOT NULL,
    total_charged_minor   BIGINT  NOT NULL DEFAULT 0,
    total_refunded_minor  BIGINT  NOT NULL DEFAULT 0,
    successful_payments   INT     NOT NULL DEFAULT 0,
    failed_payments       INT     NOT NULL DEFAULT 0,
    last_payment_at       TIMESTAMP,
    updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (merchant_id, customer_id)
);
CREATE INDEX IF NOT EXISTS idx_cpsp_updated_at ON customer_payment_summary_projection (updated_at);

-- ── 2. Ledger balance projection ─────────────────────────────────────────────
-- Per-(merchant, user) running balance derived from ledger entries.
-- Distinct from ledger_balance_snapshot (which is a point-in-time snapshot);
-- this projection is continuously updated on every relevant domain event.
CREATE TABLE IF NOT EXISTS ledger_balance_projection (
    merchant_id          BIGINT  NOT NULL,
    user_id              BIGINT  NOT NULL,
    total_credits_minor  BIGINT  NOT NULL DEFAULT 0,
    total_debits_minor   BIGINT  NOT NULL DEFAULT 0,
    net_balance_minor    BIGINT  NOT NULL DEFAULT 0,
    entry_count          INT     NOT NULL DEFAULT 0,
    last_entry_at        TIMESTAMP,
    updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (merchant_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_lbp_updated_at ON ledger_balance_projection (updated_at);

-- ── 3. Merchant revenue projection ──────────────────────────────────────────
-- Per-merchant rolling revenue / churn counters.
-- Distinct from merchant_daily_kpis_projection (date-bucketed KPIs); this is
-- a single all-time aggregate row per merchant.
CREATE TABLE IF NOT EXISTS merchant_revenue_projection (
    merchant_id           BIGINT  NOT NULL,
    total_revenue_minor   BIGINT  NOT NULL DEFAULT 0,
    total_refunds_minor   BIGINT  NOT NULL DEFAULT 0,
    net_revenue_minor     BIGINT  NOT NULL DEFAULT 0,
    active_subscriptions  INT     NOT NULL DEFAULT 0,
    churned_subscriptions INT     NOT NULL DEFAULT 0,
    updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (merchant_id)
);
CREATE INDEX IF NOT EXISTS idx_mrp_updated_at ON merchant_revenue_projection (updated_at);
