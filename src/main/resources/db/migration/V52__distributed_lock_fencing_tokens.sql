-- Phase 6: Distributed lock fencing tokens
--
-- Adds last_fence_token columns to the three high-contention entity tables so that
-- downstream DB writes can be rejected when a stale fence token is presented.
-- A fence token is a monotonically increasing counter generated via Redis INCR on
-- the fence key (prod:firstclub:fence:{resourceType}:{resourceId}).  Any write that
-- presents a token < the stored last_fence_token is from a process whose distributed
-- lock has already expired or been superseded.

-- ── Fence token columns ──────────────────────────────────────────────────────

ALTER TABLE subscriptions_v2
    ADD COLUMN IF NOT EXISTS last_fence_token BIGINT NOT NULL DEFAULT 0;

ALTER TABLE payment_intents_v2
    ADD COLUMN IF NOT EXISTS last_fence_token BIGINT NOT NULL DEFAULT 0;

ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS last_fence_token BIGINT NOT NULL DEFAULT 0;

-- ── Distributed lock audit table (optional, for observability) ────────────────
--
-- Records every lock acquisition and release for debugging and compliance.
-- This table is append-only; rows are never updated after insert.
-- The expired flag is set asynchronously by a cleanup job when a lock TTL
-- elapsed without an explicit release (i.e. the lock timed out).

CREATE TABLE IF NOT EXISTS distributed_lock_audit (
    id              BIGSERIAL       PRIMARY KEY,
    resource_type   VARCHAR(100)    NOT NULL,
    resource_id     VARCHAR(255)    NOT NULL,
    lock_owner      VARCHAR(512)    NOT NULL,   -- {instanceId}:{threadId}:{uuid}
    fence_token     BIGINT          NOT NULL,
    acquired_at     TIMESTAMP       NOT NULL,
    released_at     TIMESTAMP,                  -- NULL until explicitly released
    expired         BOOLEAN         NOT NULL DEFAULT FALSE
);

-- Index for common queries: find all locks for a resource, find unreleased locks
CREATE INDEX IF NOT EXISTS idx_dla_resource
    ON distributed_lock_audit (resource_type, resource_id, acquired_at DESC);

CREATE INDEX IF NOT EXISTS idx_dla_unreleased
    ON distributed_lock_audit (acquired_at)
    WHERE released_at IS NULL AND expired = FALSE;
