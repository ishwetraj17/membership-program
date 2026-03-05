-- V1__init_schema.sql
-- Initial schema for FirstClub Membership Program
-- Mirrors the JPA entity definitions — used in prod with spring.flyway.enabled=true

-- ─── Users ────────────────────────────────────────────────────────────────────
CREATE TABLE users (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(100)        NOT NULL,
    email               VARCHAR(150)        NOT NULL UNIQUE,
    password            VARCHAR(255)        NOT NULL,
    phone_number        VARCHAR(20),
    address             TEXT,
    city                VARCHAR(100),
    state               VARCHAR(100),
    pincode             VARCHAR(10),
    status              VARCHAR(20)         NOT NULL DEFAULT 'ACTIVE',
    is_deleted          BOOLEAN             NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP           NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_email       ON users (email);
CREATE INDEX idx_user_status      ON users (status);
CREATE INDEX idx_user_is_deleted  ON users (is_deleted);

-- User roles (ElementCollection)
CREATE TABLE user_roles (
    user_id BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role    VARCHAR(50)  NOT NULL,
    PRIMARY KEY (user_id, role)
);

-- ─── Membership Tiers ─────────────────────────────────────────────────────────
CREATE TABLE membership_tiers (
    id                      BIGSERIAL PRIMARY KEY,
    name                    VARCHAR(50)         NOT NULL UNIQUE,
    level                   INTEGER             NOT NULL,
    description             TEXT,
    discount_percentage     NUMERIC(5, 2)       NOT NULL DEFAULT 0,
    free_delivery           BOOLEAN             NOT NULL DEFAULT FALSE,
    exclusive_deals         BOOLEAN             NOT NULL DEFAULT FALSE,
    early_access            BOOLEAN             NOT NULL DEFAULT FALSE,
    priority_support        BOOLEAN             NOT NULL DEFAULT FALSE,
    max_coupons_per_month   INTEGER             NOT NULL DEFAULT 0,
    delivery_days           INTEGER             NOT NULL DEFAULT 7,
    additional_benefits     TEXT
);

-- ─── Membership Plans ─────────────────────────────────────────────────────────
CREATE TABLE membership_plans (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(100)        NOT NULL,
    description         TEXT,
    price               NUMERIC(10, 2)      NOT NULL,
    duration_in_months  INTEGER             NOT NULL,
    type                VARCHAR(20)         NOT NULL,
    is_active           BOOLEAN             NOT NULL DEFAULT TRUE,
    tier_id             BIGINT              NOT NULL REFERENCES membership_tiers (id),
    created_at          TIMESTAMP           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP           NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_plan_tier_id  ON membership_plans (tier_id);
CREATE INDEX idx_plan_type     ON membership_plans (type);
CREATE INDEX idx_plan_active   ON membership_plans (is_active);

-- ─── Subscriptions ────────────────────────────────────────────────────────────
CREATE TABLE subscriptions (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT              NOT NULL REFERENCES users (id),
    plan_id         BIGINT              NOT NULL REFERENCES membership_plans (id),
    status          VARCHAR(20)         NOT NULL DEFAULT 'ACTIVE',
    start_date      TIMESTAMP           NOT NULL,
    end_date        TIMESTAMP           NOT NULL,
    auto_renew      BOOLEAN             NOT NULL DEFAULT TRUE,
    paid_amount     NUMERIC(10, 2)      NOT NULL,
    cancel_reason   TEXT,
    created_at      TIMESTAMP           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP           NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_subscription_user_id   ON subscriptions (user_id);
CREATE INDEX idx_subscription_status    ON subscriptions (status);
CREATE INDEX idx_subscription_end_date  ON subscriptions (end_date);
