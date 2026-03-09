-- V42: Ops and Summary Projection Tables
-- Phase 11: Projection Expansion for Support, Ops, and Fast Reads
-- All four tables are pure read models — never source-of-truth.
-- Rebuilt deterministically from domain_events via ProjectionRebuildService.

-- ── 1. subscription_status_projection ────────────────────────────────────────
-- Denormalised per-subscription operational snapshot: status, billing timeline,
-- dunning state, and payment health.  Updated on subscription lifecycle events,
-- invoice events, and payment events.
CREATE TABLE subscription_status_projection (
    merchant_id           BIGINT         NOT NULL,
    subscription_id       BIGINT         NOT NULL,
    customer_id           BIGINT         NOT NULL,
    status                VARCHAR(32)    NOT NULL,
    next_billing_at       TIMESTAMP,
    dunning_state         VARCHAR(32),
    unpaid_invoice_count  INT            NOT NULL DEFAULT 0,
    last_payment_status   VARCHAR(64),
    updated_at            TIMESTAMP      NOT NULL,
    PRIMARY KEY (merchant_id, subscription_id)
);

CREATE INDEX idx_ssp_merchant_status  ON subscription_status_projection (merchant_id, status);
CREATE INDEX idx_ssp_customer         ON subscription_status_projection (merchant_id, customer_id);
CREATE INDEX idx_ssp_next_billing     ON subscription_status_projection (next_billing_at)
    WHERE status NOT IN ('CANCELLED', 'EXPIRED');

-- ── 2. invoice_summary_projection ────────────────────────────────────────────
-- Lightweight invoice read model for support and billing dashboards. Updated on
-- INVOICE_CREATED and PAYMENT_SUCCEEDED events.
CREATE TABLE invoice_summary_projection (
    merchant_id     BIGINT         NOT NULL,
    invoice_id      BIGINT         NOT NULL,
    invoice_number  VARCHAR(64),
    customer_id     BIGINT         NOT NULL,
    status          VARCHAR(32)    NOT NULL,
    subtotal        NUMERIC(18, 4) NOT NULL DEFAULT 0,
    tax_total       NUMERIC(18, 4) NOT NULL DEFAULT 0,
    grand_total     NUMERIC(18, 4) NOT NULL DEFAULT 0,
    paid_at         TIMESTAMP,
    overdue_flag    BOOLEAN        NOT NULL DEFAULT FALSE,
    updated_at      TIMESTAMP      NOT NULL,
    PRIMARY KEY (merchant_id, invoice_id)
);

CREATE INDEX idx_isp_merchant_status ON invoice_summary_projection (merchant_id, status);
CREATE INDEX idx_isp_customer        ON invoice_summary_projection (merchant_id, customer_id);
CREATE INDEX idx_isp_overdue         ON invoice_summary_projection (merchant_id, overdue_flag)
    WHERE overdue_flag = TRUE;

-- ── 3. payment_summary_projection ────────────────────────────────────────────
-- Payment intent read model for ops dashboards; tracks capture, refund, dispute
-- amounts, attempt counts, and last gateway / failure signal.
CREATE TABLE payment_summary_projection (
    merchant_id           BIGINT         NOT NULL,
    payment_intent_id     BIGINT         NOT NULL,
    customer_id           BIGINT         NOT NULL,
    invoice_id            BIGINT,
    status                VARCHAR(32)    NOT NULL,
    captured_amount       NUMERIC(18, 4) NOT NULL DEFAULT 0,
    refunded_amount       NUMERIC(18, 4) NOT NULL DEFAULT 0,
    disputed_amount       NUMERIC(18, 4) NOT NULL DEFAULT 0,
    attempt_count         INT            NOT NULL DEFAULT 0,
    last_gateway          VARCHAR(64),
    last_failure_category VARCHAR(64),
    updated_at            TIMESTAMP      NOT NULL,
    PRIMARY KEY (merchant_id, payment_intent_id)
);

CREATE INDEX idx_psp_merchant_status ON payment_summary_projection (merchant_id, status);
CREATE INDEX idx_psp_customer        ON payment_summary_projection (merchant_id, customer_id);
CREATE INDEX idx_psp_invoice         ON payment_summary_projection (invoice_id)
    WHERE invoice_id IS NOT NULL;

-- ── 4. recon_dashboard_projection ────────────────────────────────────────────
-- Per-date reconciliation dashboard roll-up.  merchant_id = NULL means
-- platform-aggregate (cross-merchant). UNIQUE on (COALESCE(merchant_id,-1), business_date)
-- prevents duplicate rows without relying on nullable composite PKs.
CREATE TABLE recon_dashboard_projection (
    id                BIGSERIAL      PRIMARY KEY,
    merchant_id       BIGINT,
    business_date     DATE           NOT NULL,
    layer2_open       INT            NOT NULL DEFAULT 0,
    layer3_open       INT            NOT NULL DEFAULT 0,
    layer4_open       INT            NOT NULL DEFAULT 0,
    resolved_count    INT            NOT NULL DEFAULT 0,
    unresolved_amount NUMERIC(18, 4) NOT NULL DEFAULT 0,
    updated_at        TIMESTAMP      NOT NULL
);

-- Uniqueness: treat NULL merchant_id as platform aggregate (sentinel -1)
CREATE UNIQUE INDEX uq_rdp_merchant_date ON recon_dashboard_projection
    (COALESCE(merchant_id, -1), business_date);
CREATE INDEX idx_rdp_business_date ON recon_dashboard_projection (business_date);
