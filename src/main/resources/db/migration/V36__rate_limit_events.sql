-- V36: Rate limit audit event table
-- Stores a best-effort record whenever a rate limit is exceeded.
-- Used by the ops deep-health dashboard and incident runbooks.

CREATE TABLE IF NOT EXISTS rate_limit_events (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    category    VARCHAR(64)  NOT NULL,                    -- RateLimitPolicy name
    subject_key VARCHAR(255) NOT NULL,                    -- Redis key used for this check
    merchant_id VARCHAR(255),                             -- NULL for non-merchant policies
    blocked     BOOLEAN      NOT NULL DEFAULT TRUE,
    request_id  VARCHAR(64),                              -- Idempotency-Key / correlation-ID
    reason      VARCHAR(512),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rl_events_blocked_at ON rate_limit_events (blocked, created_at);
CREATE INDEX IF NOT EXISTS idx_rl_events_category   ON rate_limit_events (category);
