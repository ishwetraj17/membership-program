-- ============================================================
-- V30 — Projection tables and ledger balance snapshots
-- Phase 16: Projection Tables and Snapshot-Based Reads
-- ============================================================

-- ── customer_billing_summary_projection ──────────────────────────────────────
-- Read model: per-customer billing summary per merchant.
-- NOT source of truth — rebuildable from domain events.
CREATE TABLE customer_billing_summary_projection (
    merchant_id                 BIGINT          NOT NULL,
    customer_id                 BIGINT          NOT NULL,
    active_subscriptions_count  INT             NOT NULL DEFAULT 0,
    unpaid_invoices_count       INT             NOT NULL DEFAULT 0,
    total_paid_amount           DECIMAL(18,4)   NOT NULL DEFAULT 0,
    total_refunded_amount       DECIMAL(18,4)   NOT NULL DEFAULT 0,
    last_payment_at             TIMESTAMP       NULL,
    updated_at                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    PRIMARY KEY (merchant_id, customer_id)
);

CREATE INDEX idx_cbsp_updated_at
    ON customer_billing_summary_projection(updated_at);

-- ── ledger_balance_snapshots ─────────────────────────────────────────────────
-- Daily (and optionally hourly) point-in-time balance per ledger account.
-- Partial unique indexes handle nullable merchant_id correctly in PostgreSQL.
CREATE TABLE ledger_balance_snapshots (
    id              BIGSERIAL       PRIMARY KEY,
    merchant_id     BIGINT          NULL,
    account_id      BIGINT          NOT NULL REFERENCES ledger_accounts(id),
    snapshot_date   DATE            NOT NULL,
    balance         DECIMAL(18,4)   NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- Platform-wide snapshots (merchant_id IS NULL)
CREATE UNIQUE INDEX uq_lb_snapshot_platform
    ON ledger_balance_snapshots(account_id, snapshot_date)
    WHERE merchant_id IS NULL;

-- Merchant-specific snapshots (merchant_id IS NOT NULL)
CREATE UNIQUE INDEX uq_lb_snapshot_merchant
    ON ledger_balance_snapshots(merchant_id, account_id, snapshot_date)
    WHERE merchant_id IS NOT NULL;

CREATE INDEX idx_lb_snapshots_date
    ON ledger_balance_snapshots(snapshot_date);

-- ── merchant_daily_kpis_projection ───────────────────────────────────────────
-- Read model: per-merchant daily operational KPI counters.
-- NOT source of truth — rebuildable from domain events.
CREATE TABLE merchant_daily_kpis_projection (
    merchant_id         BIGINT          NOT NULL,
    business_date       DATE            NOT NULL,
    invoices_created    INT             NOT NULL DEFAULT 0,
    invoices_paid       INT             NOT NULL DEFAULT 0,
    payments_captured   INT             NOT NULL DEFAULT 0,
    refunds_completed   INT             NOT NULL DEFAULT 0,
    disputes_opened     INT             NOT NULL DEFAULT 0,
    revenue_recognized  DECIMAL(18,4)   NOT NULL DEFAULT 0,
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    PRIMARY KEY (merchant_id, business_date)
);

CREATE INDEX idx_mdkp_business_date
    ON merchant_daily_kpis_projection(business_date);
