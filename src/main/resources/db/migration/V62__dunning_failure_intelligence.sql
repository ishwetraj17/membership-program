-- ============================================================================
-- V62 — Dunning failure-code intelligence and backup payment strategy
--
-- Adds per-attempt columns that record what failure code the gateway returned,
-- how the classifier categorised it, what decision the strategy engine took,
-- and whether a non-retryable failure caused early termination of the queue.
--
-- The existing `used_backup_method` column already tracks backup-PM usage, so
-- it is NOT duplicated here.
-- ============================================================================

ALTER TABLE dunning_attempts
    ADD COLUMN IF NOT EXISTS failure_code     VARCHAR(80),
    ADD COLUMN IF NOT EXISTS failure_category VARCHAR(40),
    ADD COLUMN IF NOT EXISTS decision_taken   VARCHAR(30),
    ADD COLUMN IF NOT EXISTS decision_reason  TEXT,
    ADD COLUMN IF NOT EXISTS stopped_early    BOOLEAN NOT NULL DEFAULT FALSE;

-- Index for operational queries: "show all early-stopped attempts"
CREATE INDEX IF NOT EXISTS idx_dunning_stopped_early
    ON dunning_attempts (subscription_id, stopped_early)
    WHERE stopped_early = TRUE;

-- Index for analytics: failure category breakdown
CREATE INDEX IF NOT EXISTS idx_dunning_failure_category
    ON dunning_attempts (failure_category)
    WHERE failure_category IS NOT NULL;
