-- ─────────────────────────────────────────────────────────────
-- V1: Initial schema
-- ─────────────────────────────────────────────────────────────

CREATE TABLE membership_tiers (
    id                    BIGSERIAL PRIMARY KEY,
    name                  VARCHAR(50)    NOT NULL UNIQUE,
    description           VARCHAR(255)   NOT NULL,
    level                 INTEGER        NOT NULL,
    discount_percentage   DECIMAL(5,2)   NOT NULL,
    free_delivery         BOOLEAN        NOT NULL DEFAULT FALSE,
    exclusive_deals       BOOLEAN        NOT NULL DEFAULT FALSE,
    early_access          BOOLEAN        NOT NULL DEFAULT FALSE,
    priority_support      BOOLEAN        NOT NULL DEFAULT FALSE,
    max_coupons_per_month INTEGER        NOT NULL DEFAULT 0,
    delivery_days         INTEGER        NOT NULL DEFAULT 7,
    additional_benefits   TEXT
);

CREATE TABLE membership_plans (
    id                 BIGSERIAL PRIMARY KEY,
    name               VARCHAR(100)   NOT NULL,
    description        VARCHAR(255)   NOT NULL,
    type               VARCHAR(20)    NOT NULL,
    price              DECIMAL(10,2)  NOT NULL,
    duration_in_months INTEGER        NOT NULL,
    is_active          BOOLEAN        NOT NULL DEFAULT TRUE,
    tier_id            BIGINT         NOT NULL REFERENCES membership_tiers(id)
);

CREATE TABLE users (
    id           BIGSERIAL PRIMARY KEY,
    email        VARCHAR(255) NOT NULL UNIQUE,
    name         VARCHAR(100) NOT NULL,
    phone_number VARCHAR(20)  NOT NULL,
    address      VARCHAR(255) NOT NULL,
    city         VARCHAR(100) NOT NULL,
    state        VARCHAR(100) NOT NULL,
    pincode      VARCHAR(10)  NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE subscriptions (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT         NOT NULL REFERENCES users(id),
    plan_id             BIGINT         NOT NULL REFERENCES membership_plans(id),
    status              VARCHAR(20)    NOT NULL,
    start_date          TIMESTAMP      NOT NULL,
    end_date            TIMESTAMP      NOT NULL,
    next_billing_date   TIMESTAMP      NOT NULL,
    paid_amount         DECIMAL(10,2)  NOT NULL,
    auto_renewal        BOOLEAN        NOT NULL DEFAULT TRUE,
    cancelled_at        TIMESTAMP,
    cancellation_reason TEXT,
    version             BIGINT         NOT NULL DEFAULT 0,
    created_at          TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP      NOT NULL DEFAULT NOW()
);

-- ─── Indexes ──────────────────────────────────────────────────
CREATE INDEX idx_subscriptions_user_id    ON subscriptions(user_id);
CREATE INDEX idx_subscriptions_status     ON subscriptions(status);
CREATE INDEX idx_subscriptions_end_date   ON subscriptions(end_date);
CREATE INDEX idx_plans_tier_id            ON membership_plans(tier_id);
CREATE INDEX idx_plans_type_active        ON membership_plans(type, is_active);

-- ─── Unique constraint: one ACTIVE subscription per user ──────
-- Partial index on PostgreSQL prevents a second active row without
-- blocking future subscriptions after one is cancelled/expired.
CREATE UNIQUE INDEX uq_user_active_subscription
    ON subscriptions(user_id)
    WHERE status = 'ACTIVE';
