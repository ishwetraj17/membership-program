-- V15__merchant_foundation.sql
-- Phase 1: Merchant / Tenant Foundation
-- Creates the multi-tenant merchant layer for the FirstClub fintech platform.
-- Reference data seeded here (not in @Profile("dev") runners) per global rules.

-- ─── Merchant Accounts ────────────────────────────────────────────────────────
CREATE TABLE merchant_accounts (
    id               BIGSERIAL       PRIMARY KEY,
    merchant_code    VARCHAR(64)     NOT NULL UNIQUE,
    legal_name       VARCHAR(255)    NOT NULL,
    display_name     VARCHAR(255)    NOT NULL,
    status           VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    default_currency VARCHAR(10)     NOT NULL DEFAULT 'INR',
    country_code     VARCHAR(8)      NOT NULL DEFAULT 'IN',
    timezone         VARCHAR(64)     NOT NULL DEFAULT 'Asia/Kolkata',
    support_email    VARCHAR(255)    NOT NULL,
    created_at       TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_merchant_accounts_status ON merchant_accounts (status);
CREATE INDEX idx_merchant_accounts_code   ON merchant_accounts (merchant_code);

-- ─── Merchant Users (join between merchants and platform users) ───────────────
CREATE TABLE merchant_users (
    id          BIGSERIAL   PRIMARY KEY,
    merchant_id BIGINT      NOT NULL REFERENCES merchant_accounts (id),
    user_id     BIGINT      NOT NULL REFERENCES users (id),
    role        VARCHAR(32) NOT NULL DEFAULT 'READ_ONLY',
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_merchant_user UNIQUE (merchant_id, user_id)
);

CREATE INDEX idx_merchant_users_merchant_id ON merchant_users (merchant_id);
CREATE INDEX idx_merchant_users_user_id     ON merchant_users (user_id);

-- ─── Merchant Settings (1:1 with merchant_accounts) ──────────────────────────
CREATE TABLE merchant_settings (
    id                          BIGSERIAL       PRIMARY KEY,
    merchant_id                 BIGINT          NOT NULL UNIQUE REFERENCES merchant_accounts (id),
    webhook_enabled             BOOLEAN         NOT NULL DEFAULT TRUE,
    settlement_frequency        VARCHAR(32)     NOT NULL DEFAULT 'DAILY',
    auto_retry_enabled          BOOLEAN         NOT NULL DEFAULT TRUE,
    default_grace_days          INT             NOT NULL DEFAULT 7,
    default_dunning_policy_code VARCHAR(64),
    metadata_json               TEXT,
    created_at                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMP       NOT NULL DEFAULT NOW()
);
