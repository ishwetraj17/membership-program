-- ─────────────────────────────────────────────────────────────────────────────
-- Phase 11: Integrity Audit Tables
-- Stores the history of system-wide invariant-check runs and their per-checker
-- results. Enables trend analysis, alerting, and audit trails for data
-- correctness verification.
--
-- NOTE: The original spec requested V42 but that version is already used by
-- ops_and_summary_projections. This migration is assigned V57.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── Run-level record (one row per POST /admin/integrity/check call) ──────────

CREATE TABLE integrity_check_runs (
    id             BIGSERIAL    PRIMARY KEY,
    started_at     TIMESTAMP    NOT NULL,
    completed_at   TIMESTAMP    NULL,

    -- RUNNING | COMPLETED | FAILED | ERROR
    status         VARCHAR(32)  NOT NULL,

    -- Who or what triggered this run (admin user id, "scheduler", "ci", …)
    triggered_by   VARCHAR(128) NULL,

    -- Optional correlation fields for request tracing
    request_id     VARCHAR(64)  NULL,
    correlation_id VARCHAR(64)  NULL
);

CREATE INDEX idx_integrity_runs_started_at ON integrity_check_runs(started_at DESC);
CREATE INDEX idx_integrity_runs_status     ON integrity_check_runs(status);

-- ── Per-checker result (one row per checker per run) ─────────────────────────

CREATE TABLE integrity_check_results (
    id                      BIGSERIAL    PRIMARY KEY,

    run_id                  BIGINT       NOT NULL
                                REFERENCES integrity_check_runs(id)
                                ON DELETE CASCADE,

    invariant_name          VARCHAR(128) NOT NULL,

    -- PASS | FAIL | ERROR
    status                  VARCHAR(16)  NOT NULL,

    violation_count         INT          NOT NULL DEFAULT 0,

    -- CRITICAL | HIGH | MEDIUM | LOW
    severity                VARCHAR(16)  NOT NULL,

    -- JSON array of InvariantViolation objects written by the engine at run time
    details_json            TEXT         NULL,

    suggested_repair_action TEXT         NULL,

    created_at              TIMESTAMP    NOT NULL
);

CREATE INDEX idx_integrity_results_run_id        ON integrity_check_results(run_id);
CREATE INDEX idx_integrity_results_invariant_name ON integrity_check_results(invariant_name);
CREATE INDEX idx_integrity_results_status         ON integrity_check_results(status)
    WHERE status != 'PASS';
