-- ─────────────────────────────────────────────────────────────
-- V20: Growth & retention (Phase 3)
--   - Trial memberships: a subscription can begin as an unpaid trial that
--     auto-converts to paid (or expires) at the trial end date.
--   - Introductory offers: a configurable discount on the first billing period.
--   - Savings ledger: an auditable, append-only record of realised member savings.
-- All additive — existing rows default to non-trial, and no existing flow changes.
-- ─────────────────────────────────────────────────────────────

-- Trial fields on subscriptions (existing rows are non-trial paid subscriptions).
ALTER TABLE subscriptions ADD COLUMN trial           BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE subscriptions ADD COLUMN trial_end_date  TIMESTAMP;
ALTER TABLE subscriptions ADD COLUMN trial_converted BOOLEAN NOT NULL DEFAULT FALSE;

-- Index the trial-conversion job's predicate (due trials).
CREATE INDEX idx_subscriptions_trial_due ON subscriptions(trial, status, trial_end_date);

-- Configurable first-period offers.
CREATE TABLE introductory_offers (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL UNIQUE,
    description VARCHAR(255) NOT NULL,
    offer_type  VARCHAR(20)  NOT NULL,
    offer_value NUMERIC(10,2),
    plan_id     BIGINT REFERENCES membership_plans(id),
    active      BOOLEAN      NOT NULL DEFAULT TRUE
);

-- Auditable savings ledger.
CREATE TABLE savings_ledger (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT        NOT NULL,
    savings_type     VARCHAR(30)   NOT NULL,
    product_category VARCHAR(30),
    amount           NUMERIC(12,2) NOT NULL,
    source_type      VARCHAR(20)   NOT NULL,
    source_id        BIGINT        NOT NULL,
    occurred_at      TIMESTAMP     NOT NULL,
    created_at       TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_savings_user ON savings_ledger(user_id);
CREATE INDEX idx_savings_user_occurred ON savings_ledger(user_id, occurred_at);
