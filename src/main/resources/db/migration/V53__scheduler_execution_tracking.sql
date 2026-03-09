-- ─────────────────────────────────────────────────────────────────────────────
-- V53  Scheduler execution tracking + advisory lock audit
--
-- PURPOSE:
--   Enables scheduler singleton safety via PostgreSQL advisory locks and
--   provides a durable execution history for operational observability.
--
--   Two tables are created:
--   1. scheduler_execution_history  — per-run execution record with timing,
--      status, node ownership, and processed-item counts.
--   2. No schema needed for advisory locks — they are in-memory PostgreSQL
--      constructs managed by pg_try_advisory_xact_lock / pg_advisory_lock.
--
-- NOTE ON V38:
--   V38 was used by business-fingerprint tables. This migration is V53 and
--   is the canonical Phase-7 scheduler tracking migration.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS scheduler_execution_history (
    id                BIGSERIAL       NOT NULL,
    scheduler_name    VARCHAR(128)    NOT NULL,
    node_id           VARCHAR(255)    NOT NULL,
    started_at        TIMESTAMPTZ     NOT NULL,
    completed_at      TIMESTAMPTZ     NULL,
    status            VARCHAR(32)     NOT NULL,
    processed_count   INTEGER         NULL,
    error_message     TEXT            NULL,
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_scheduler_execution_history PRIMARY KEY (id),
    CONSTRAINT chk_scheduler_status
        CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED'))
);

-- Index for lookups by scheduler name (latest run queries, stale detection)
CREATE INDEX IF NOT EXISTS idx_sch_exec_name_started
    ON scheduler_execution_history (scheduler_name, started_at DESC);

-- Index for node-level diagnostics
CREATE INDEX IF NOT EXISTS idx_sch_exec_node_id
    ON scheduler_execution_history (node_id);

-- Index for status-based filtering (find recent failures quickly)
CREATE INDEX IF NOT EXISTS idx_sch_exec_status_created
    ON scheduler_execution_history (status, created_at DESC);

COMMENT ON TABLE scheduler_execution_history IS
    'Durable audit log for scheduler singleton execution. Each scheduler run '
    'writes a row on start and updates it on completion or failure. '
    'Advisory lock acquisition is tracked implicitly via absence of '
    'RUNNING rows from competing nodes.';

COMMENT ON COLUMN scheduler_execution_history.scheduler_name IS
    'Logical name of the scheduler (e.g. idempotency-cleanup, subscription-renewal).';
COMMENT ON COLUMN scheduler_execution_history.node_id IS
    'Stable node identity: hostname + UUID prefix, from LockOwnerIdentityProvider.';
COMMENT ON COLUMN scheduler_execution_history.status IS
    'RUNNING = in-progress, SUCCESS = completed normally, '
    'FAILED = exception thrown, SKIPPED = lock not acquired or primary check failed.';
COMMENT ON COLUMN scheduler_execution_history.processed_count IS
    'Optional count of items processed (e.g. subscriptions renewed, keys expired).';
COMMENT ON COLUMN scheduler_execution_history.error_message IS
    'First 4000 chars of exception message when status = FAILED.';
