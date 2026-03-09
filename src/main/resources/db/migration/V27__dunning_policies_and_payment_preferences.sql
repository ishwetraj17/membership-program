-- ============================================================
-- V27 — Dunning 2.0: policies, payment preferences, attempt tracking
-- ============================================================

-- ── dunning_policies ─────────────────────────────────────────────────────────
CREATE TABLE dunning_policies (
    id                                BIGSERIAL    PRIMARY KEY,
    merchant_id                       BIGINT        NOT NULL REFERENCES merchant_accounts(id),
    policy_code                       VARCHAR(64)   NOT NULL,
    retry_offsets_json                TEXT          NOT NULL,
    max_attempts                      INT           NOT NULL,
    grace_days                        INT           NOT NULL,
    fallback_to_backup_payment_method BOOLEAN       NOT NULL DEFAULT FALSE,
    status_after_exhaustion           VARCHAR(32)   NOT NULL,
    created_at                        TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_dunning_policy_merchant_code  UNIQUE (merchant_id, policy_code),
    CONSTRAINT ck_dunning_policy_max_attempts   CHECK (max_attempts > 0),
    CONSTRAINT ck_dunning_policy_grace_days     CHECK (grace_days >= 0),
    CONSTRAINT ck_dunning_policy_status         CHECK (
        status_after_exhaustion IN ('SUSPENDED', 'CANCELLED')
    )
);

CREATE INDEX idx_dunning_policies_merchant ON dunning_policies(merchant_id);

-- ── subscription_payment_preferences ────────────────────────────────────────
CREATE TABLE subscription_payment_preferences (
    id                          BIGSERIAL   PRIMARY KEY,
    subscription_id             BIGINT      NOT NULL UNIQUE REFERENCES subscriptions_v2(id),
    primary_payment_method_id   BIGINT      NOT NULL REFERENCES payment_methods(id),
    backup_payment_method_id    BIGINT      NULL     REFERENCES payment_methods(id),
    retry_order_json            TEXT        NULL,
    created_at                  TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sub_payment_pref_sub ON subscription_payment_preferences(subscription_id);

-- ── Extend dunning_attempts for v2 policy-driven tracking ────────────────────
-- Nullable so existing v1 rows are unaffected
ALTER TABLE dunning_attempts
    ADD COLUMN IF NOT EXISTS dunning_policy_id  BIGINT  NULL REFERENCES dunning_policies(id),
    ADD COLUMN IF NOT EXISTS payment_method_id  BIGINT  NULL REFERENCES payment_methods(id),
    ADD COLUMN IF NOT EXISTS used_backup_method BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_dunning_attempts_policy ON dunning_attempts(dunning_policy_id)
    WHERE dunning_policy_id IS NOT NULL;
