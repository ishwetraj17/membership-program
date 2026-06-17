-- ─────────────────────────────────────────────────────────────
-- V6: Subscription event / billing history (append-only)
-- Source of truth for lifetime revenue and a full audit trail of
-- every lifecycle change. Denormalised on purpose (raw ids).
-- ─────────────────────────────────────────────────────────────

CREATE TABLE subscription_events (
    id              BIGSERIAL PRIMARY KEY,
    subscription_id BIGINT        NOT NULL,
    user_id         BIGINT        NOT NULL,
    event_type      VARCHAR(20)   NOT NULL,
    amount          DECIMAL(10,2) NOT NULL DEFAULT 0,
    plan_id         BIGINT,
    tier_name       VARCHAR(50),
    occurred_at     TIMESTAMP     NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_subscription_events_subscription_id ON subscription_events(subscription_id);
CREATE INDEX idx_subscription_events_user_id         ON subscription_events(user_id);
CREATE INDEX idx_subscription_events_type            ON subscription_events(event_type);
