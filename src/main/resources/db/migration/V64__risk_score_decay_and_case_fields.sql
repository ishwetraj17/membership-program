-- ─────────────────────────────────────────────────────────────────────────────
-- Phase 18 : Risk score decay, manual review SLA / escalation, explainable decisions
-- ─────────────────────────────────────────────────────────────────────────────
-- NOTE: The spec requested V49 but V49 is already taken by
--       V49__support_ops_tracking.sql, so we use V64 (next free slot).

-- ── manual_review_cases ──────────────────────────────────────────────────────
-- SLA deadline: when the case must be resolved by.
ALTER TABLE manual_review_cases
    ADD COLUMN IF NOT EXISTS sla_due_at TIMESTAMP NULL;

-- Timestamp when the case was escalated (OPEN → ESCALATED transition).
ALTER TABLE manual_review_cases
    ADD COLUMN IF NOT EXISTS escalated_at TIMESTAMP NULL;

-- Human-readable reason for the final approve/reject/close decision.
ALTER TABLE manual_review_cases
    ADD COLUMN IF NOT EXISTS decision_reason TEXT NULL;

-- User ID (or service name) that closed/approved/rejected the case.
ALTER TABLE manual_review_cases
    ADD COLUMN IF NOT EXISTS closed_by BIGINT NULL;

-- Timestamp of the terminal transition (APPROVED / REJECTED / CLOSED).
ALTER TABLE manual_review_cases
    ADD COLUMN IF NOT EXISTS closed_at TIMESTAMP NULL;

-- Fast index: cases overdue (SLA exceeded but still open/escalated)
CREATE INDEX IF NOT EXISTS idx_review_cases_sla
    ON manual_review_cases (sla_due_at)
    WHERE status IN ('OPEN', 'ESCALATED');

-- ── risk_events ──────────────────────────────────────────────────────────────
-- Raw score before time-decay is applied.
ALTER TABLE risk_events
    ADD COLUMN IF NOT EXISTS base_score INT NULL;

-- Score after half-life decay based on event age.
ALTER TABLE risk_events
    ADD COLUMN IF NOT EXISTS decayed_score INT NULL;

-- Risk action decided at the time of this event (ALLOW / CHALLENGE / REVIEW / BLOCK).
ALTER TABLE risk_events
    ADD COLUMN IF NOT EXISTS decision VARCHAR(16) NULL;

-- JSON array of rule IDs that fired and contributed to this event's score.
ALTER TABLE risk_events
    ADD COLUMN IF NOT EXISTS rule_ids_json TEXT NULL;
