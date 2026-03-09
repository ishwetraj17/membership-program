-- Phase 7: Integrity / Invariant Engine persistence tables

-- ── Integrity check runs ────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS integrity_check_runs (
    id                    BIGSERIAL PRIMARY KEY,
    started_at            TIMESTAMP NOT NULL,
    finished_at           TIMESTAMP,
    initiated_by_user_id  BIGINT,
    status                VARCHAR(32)  NOT NULL DEFAULT 'RUNNING',  -- RUNNING, COMPLETED, PARTIAL_FAILURE, ERROR
    total_checks          INT          NOT NULL DEFAULT 0,
    failed_checks         INT          NOT NULL DEFAULT 0,
    summary_json          TEXT,
    merchant_id           BIGINT,     -- NULL = platform-wide run
    invariant_key         VARCHAR(128) -- NULL = all checks; set when single-check run
);

CREATE INDEX idx_icr_started_at      ON integrity_check_runs (started_at DESC);
CREATE INDEX idx_icr_status          ON integrity_check_runs (status);
CREATE INDEX idx_icr_merchant        ON integrity_check_runs (merchant_id) WHERE merchant_id IS NOT NULL;

-- ── Integrity check findings (one row per checker per run) ──────────────────

CREATE TABLE IF NOT EXISTS integrity_check_findings (
    id                    BIGSERIAL PRIMARY KEY,
    run_id                BIGINT       NOT NULL REFERENCES integrity_check_runs(id),
    invariant_key         VARCHAR(128) NOT NULL,
    severity              VARCHAR(32)  NOT NULL,  -- CRITICAL, HIGH, MEDIUM, LOW
    status                VARCHAR(16)  NOT NULL,  -- PASS, FAIL, ERROR
    violation_count       INT          NOT NULL DEFAULT 0,
    details_json          TEXT,                   -- JSON: violations preview + message
    suggested_repair_key  VARCHAR(128),
    created_at            TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_icf_run_id          ON integrity_check_findings (run_id);
CREATE INDEX idx_icf_invariant_key   ON integrity_check_findings (invariant_key);
CREATE INDEX idx_icf_severity_status ON integrity_check_findings (severity, status);
