-- V18: Subscription contracts (v2) and subscription schedules
-- Purpose: introduces a true subscription contract model with price-version
--          awareness, billing anchor, trial support, pause/cancel mechanics,
--          and a scheduled-action queue.

-- ─────────────────────────────────────────────────────────────────────────────
-- Table: subscriptions_v2
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE subscriptions_v2 (
    id                    BIGSERIAL       PRIMARY KEY,

    -- Tenant
    merchant_id           BIGINT          NOT NULL REFERENCES merchant_accounts(id),

    -- References
    customer_id           BIGINT          NOT NULL REFERENCES customers(id),
    product_id            BIGINT          NOT NULL REFERENCES products(id),
    price_id              BIGINT          NOT NULL REFERENCES prices(id),
    price_version_id      BIGINT          NOT NULL REFERENCES price_versions(id),

    -- Lifecycle
    status                VARCHAR(32)     NOT NULL,

    -- Billing
    billing_anchor_at     TIMESTAMP       NOT NULL,
    current_period_start  TIMESTAMP,
    current_period_end    TIMESTAMP,
    next_billing_at       TIMESTAMP,

    -- Cancel
    cancel_at_period_end  BOOLEAN         NOT NULL DEFAULT FALSE,
    cancelled_at          TIMESTAMP,

    -- Pause
    pause_starts_at       TIMESTAMP,
    pause_ends_at         TIMESTAMP,

    -- Trial
    trial_ends_at         TIMESTAMP,

    -- Free-form merchant metadata
    metadata_json         TEXT,

    -- Optimistic locking
    version               BIGINT          NOT NULL DEFAULT 0,

    -- Timestamps
    created_at            TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Indexes: subscriptions_v2
-- ─────────────────────────────────────────────────────────────────────────────
CREATE INDEX idx_sub_v2_merchant_customer_status
    ON subscriptions_v2 (merchant_id, customer_id, status);

CREATE INDEX idx_sub_v2_merchant_next_billing
    ON subscriptions_v2 (merchant_id, next_billing_at, status);

-- ─────────────────────────────────────────────────────────────────────────────
-- Table: subscription_schedules
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE subscription_schedules (
    id                 BIGSERIAL   PRIMARY KEY,
    subscription_id    BIGINT      NOT NULL REFERENCES subscriptions_v2(id),
    scheduled_action   VARCHAR(32) NOT NULL,
    effective_at       TIMESTAMP   NOT NULL,
    payload_json       TEXT,
    status             VARCHAR(32) NOT NULL,
    created_at         TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Indexes: subscription_schedules
-- ─────────────────────────────────────────────────────────────────────────────
CREATE INDEX idx_sub_schedule_sub_id_effective_at
    ON subscription_schedules (subscription_id, effective_at);
