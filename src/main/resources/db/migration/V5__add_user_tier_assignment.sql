-- ─────────────────────────────────────────────────────────────
-- V5: Earned-tier assignment
-- The tier a user has earned from order activity (distinct from the
-- tier they purchase via a subscription). One row per user.
-- ─────────────────────────────────────────────────────────────

CREATE TABLE user_tier_assignment (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL UNIQUE REFERENCES users(id),
    tier_id      BIGINT      NOT NULL REFERENCES membership_tiers(id),
    source       VARCHAR(20) NOT NULL DEFAULT 'AUTO',
    evaluated_at TIMESTAMP   NOT NULL,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_tier_assignment_user_id ON user_tier_assignment(user_id);
