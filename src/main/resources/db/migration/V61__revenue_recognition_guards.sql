-- ============================================================
-- Phase 15: Revenue recognition guards and deferred revenue correctness
--
-- Adds guard decision audit columns and minor-unit amount tracking
-- to revenue_recognition_schedules, enabling explicit policy
-- enforcement per subscription status.
-- ============================================================

ALTER TABLE revenue_recognition_schedules
    ADD COLUMN expected_amount_minor     BIGINT,
    ADD COLUMN recognized_amount_minor   BIGINT,
    ADD COLUMN rounding_adjustment_minor BIGINT,
    ADD COLUMN policy_code               VARCHAR(40),
    ADD COLUMN guard_decision            VARCHAR(20),
    ADD COLUMN guard_reason              TEXT;

-- Index for querying by guard outcome (operators filtering blocked/halted rows)
CREATE INDEX idx_rev_sched_guard_decision
    ON revenue_recognition_schedules (guard_decision)
    WHERE guard_decision IS NOT NULL;

-- Index for querying by recognition policy (e.g. all DEFER_UNTIL_PAID rows)
CREATE INDEX idx_rev_sched_policy_code
    ON revenue_recognition_schedules (policy_code)
    WHERE policy_code IS NOT NULL;
