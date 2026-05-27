-- ─────────────────────────────────────────────────────────────
-- V2: Tier eligibility criteria
-- Defines the minimum order count and spend thresholds that
-- a user must meet to qualify for each tier.
-- ─────────────────────────────────────────────────────────────

CREATE TABLE tier_eligibility_criteria (
    id                     BIGSERIAL PRIMARY KEY,
    tier_id                BIGINT         NOT NULL UNIQUE REFERENCES membership_tiers(id),
    min_orders             INTEGER        NOT NULL DEFAULT 0,
    min_monthly_spend      DECIMAL(10,2)  NOT NULL DEFAULT 0,
    cohort_code            VARCHAR(50),
    evaluation_period_days INTEGER        NOT NULL DEFAULT 30,
    created_at             TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tier_eligibility_tier_id ON tier_eligibility_criteria(tier_id);
