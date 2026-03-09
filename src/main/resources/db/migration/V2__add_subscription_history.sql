-- V2__add_subscription_history.sql
-- Adds the subscription_history audit table.
-- This table records every lifecycle event for each subscription
-- (creation, upgrade, downgrade, cancellation, renewal, expiry).

CREATE TABLE subscription_history (
    id                  BIGSERIAL PRIMARY KEY,
    subscription_id     BIGINT              NOT NULL REFERENCES subscriptions (id),
    event_type          VARCHAR(20)         NOT NULL,
    old_plan_id         BIGINT,
    new_plan_id         BIGINT,
    old_status          VARCHAR(20),
    new_status          VARCHAR(20),
    reason              VARCHAR(500),
    changed_by_user_id  BIGINT,
    changed_at          TIMESTAMP           NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sub_history_subscription_id ON subscription_history (subscription_id);
CREATE INDEX idx_sub_history_changed_at      ON subscription_history (changed_at);
