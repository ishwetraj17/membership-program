-- =============================================================================
-- V67: PostgreSQL Hardening — Partial Indexes, Partitioning Readiness, RLS
-- =============================================================================
-- Phase 22: Push correctness and performance down into the database.
--
-- SECTION 1 — PARTIAL INDEXES ON HOT QUERY PATTERNS
--   Narrows each index to only the rows the application actually reads,
--   reducing both index size and planner scan cost.
--
-- SECTION 2 — PARTITIONING READINESS
--   Provides a PL/pgSQL helper to create monthly child partitions and a
--   metadata registry consumed by PartitionManager on startup.
--   The four high-volume append-only tables (outbox_events, domain_events,
--   audit_entries, ops_timeline_events) are the partition candidates.
--
--   NOTE: Converting an existing table to PARTITION BY RANGE requires a
--   maintenance-window data migration (CREATE NEW → COPY → RENAME). This
--   migration prepares the infrastructure and registry only; actual table
--   conversion is a separate DBA operation.
--
-- SECTION 3 — ROW-LEVEL SECURITY (TENANT ISOLATION)
--   Enable RLS on the four most sensitive tenant-scoped tables. Policies
--   enforce that a session can only access rows whose merchant_id matches
--   the Postgres session variable app.current_merchant_id, set by
--   RlsTenantContextConfigurer at the start of each merchant-scoped
--   transaction using SET LOCAL.
--
-- IMPORTANT — CONCURRENTLY is intentionally omitted:
--   Flyway wraps each migration in a transaction and CREATE INDEX
--   CONCURRENTLY cannot execute inside a transaction block.
-- =============================================================================


-- =============================================================================
-- SECTION 1: PARTIAL INDEXES
-- =============================================================================

-- 1a. outbox_events — retryable events scoped to merchant
--   The multi-tenant outbox dispatcher queries by (merchant_id, next_attempt_at)
--   filtering for retryable states. V47/V58 added per-status partial indexes but
--   none include merchant_id, so per-merchant dispatch still scans all retryable
--   events. This index adds merchant_id and covers both NEW and FAILED states
--   (FAILED rows are rescheduled by the retry policy and become re-retryable).
CREATE INDEX IF NOT EXISTS idx_outbox_retryable_by_merchant
    ON outbox_events (merchant_id, next_attempt_at ASC)
    WHERE status IN ('NEW', 'FAILED');

-- 1b. subscriptions_v2 — active subscriptions ready for renewal
--   The renewal scheduler queries: WHERE status='ACTIVE' AND next_billing_at <= :now
--   Adding merchant_id lets multi-tenant renewal loops skip inactive/cancelled rows
--   completely, enabling index-range scans per merchant with no heap scan.
CREATE INDEX IF NOT EXISTS idx_subscriptions_v2_active_renewal
    ON subscriptions_v2 (merchant_id, next_billing_at ASC)
    WHERE status = 'ACTIVE';

-- 1c. invoices — unpaid by merchant
--   The collections service and dunning trigger query:
--   WHERE status IN ('OPEN','PAST_DUE') AND due_date <= :now
--   Without this index the planner must scan all invoices including paid/voided.
--   The partial filter keeps the index footprint proportional to unpaid volume.
CREATE INDEX IF NOT EXISTS idx_invoices_unpaid_by_merchant
    ON invoices (merchant_id, due_date ASC)
    WHERE status IN ('OPEN', 'PAST_DUE');

-- 1d. payment_intents_v2 — succeeded intents by merchant for reconciliation
--   Reconciliation and financial reporting filter WHERE status='SUCCEEDED'.
--   V41 added (id, status) for terminal-state guard but has no merchant_id and
--   covers all statuses. This sparse index cuts recon scan to succeeded-only.
CREATE INDEX IF NOT EXISTS idx_pi_v2_succeeded_by_merchant
    ON payment_intents_v2 (merchant_id, created_at DESC)
    WHERE status = 'SUCCEEDED';

-- 1e. domain_events — causation chain lookup
--   Saga / process-manager tracing resolves causation_id → parent event.
--   Most events have no causation_id; the sparse partial index is tiny but
--   makes O(1) causation lookups possible instead of a full-table scan.
CREATE INDEX IF NOT EXISTS idx_domain_events_causation
    ON domain_events (causation_id)
    WHERE causation_id IS NOT NULL;

-- 1f. revenue_recognition_schedules — pending schedules by merchant
--   The RR runner polls: WHERE status='PENDING' AND recognition_date <= :today
--   V41 added (recognition_date, status) WHERE PENDING for the global scan;
--   this variant adds merchant_id enabling efficient per-merchant RR processing.
CREATE INDEX IF NOT EXISTS idx_rr_schedules_pending_merchant
    ON revenue_recognition_schedules (merchant_id, recognition_date ASC)
    WHERE status = 'PENDING';


-- =============================================================================
-- SECTION 2: PARTITIONING READINESS
-- =============================================================================

-- Registry: PartitionManager reads and writes this table to track which child
-- partitions have been created/verified for each managed parent table.
CREATE TABLE IF NOT EXISTS partition_management_log (
    id              BIGSERIAL    PRIMARY KEY,
    parent_table    VARCHAR(128) NOT NULL,
    partition_name  VARCHAR(128) NOT NULL,
    partition_start DATE         NOT NULL,
    partition_end   DATE         NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_pml_partition UNIQUE (parent_table, partition_name)
);

CREATE INDEX IF NOT EXISTS idx_pml_parent_start
    ON partition_management_log (parent_table, partition_start);

-- Helper function: create_monthly_partition(parent_table, year, month)
--
--   Creates "parent_table_YYYY_MM" as a RANGE partition covering
--   [month-start, next-month-start). Safe to call repeatedly (IF NOT EXISTS).
--   Returns the partition name on success, NULL when the parent is not yet
--   a partitioned table (deferred conversion scenario).
--
--   Called by PartitionManager.createPartition() on startup and monthly scheduler.
CREATE OR REPLACE FUNCTION create_monthly_partition(
    p_parent_table  TEXT,
    p_year          INT,
    p_month         INT
) RETURNS TEXT
LANGUAGE plpgsql
AS $$
DECLARE
    v_partition_name TEXT;
    v_start_date     DATE;
    v_end_date       DATE;
    v_sql            TEXT;
BEGIN
    v_start_date     := make_date(p_year, p_month, 1);
    v_end_date       := v_start_date + INTERVAL '1 month';
    v_partition_name := p_parent_table || '_' || to_char(v_start_date, 'YYYY_MM');

    v_sql := format(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF %I '
        'FOR VALUES FROM (%L) TO (%L)',
        v_partition_name,
        p_parent_table,
        v_start_date::TEXT,
        v_end_date::TEXT
    );

    BEGIN
        EXECUTE v_sql;
        RETURN v_partition_name;
    EXCEPTION
        WHEN undefined_table THEN
            RAISE NOTICE
                'Parent table % is not a partitioned table — partition creation deferred.',
                p_parent_table;
            RETURN NULL;
        WHEN OTHERS THEN
            RAISE NOTICE
                'Partition % could not be created (may already exist): %',
                v_partition_name, SQLERRM;
            RETURN v_partition_name;
    END;
END;
$$;

-- Seed the registry for the current and next 2 months across all managed tables.
-- PartitionManager refreshes this table on startup so these rows are advisory.
INSERT INTO partition_management_log
    (parent_table, partition_name, partition_start, partition_end)
VALUES
    ('outbox_events',       'outbox_events_2026_03',       '2026-03-01', '2026-04-01'),
    ('outbox_events',       'outbox_events_2026_04',       '2026-04-01', '2026-05-01'),
    ('outbox_events',       'outbox_events_2026_05',       '2026-05-01', '2026-06-01'),
    ('domain_events',       'domain_events_2026_03',       '2026-03-01', '2026-04-01'),
    ('domain_events',       'domain_events_2026_04',       '2026-04-01', '2026-05-01'),
    ('domain_events',       'domain_events_2026_05',       '2026-05-01', '2026-06-01'),
    ('audit_entries',       'audit_entries_2026_03',       '2026-03-01', '2026-04-01'),
    ('audit_entries',       'audit_entries_2026_04',       '2026-04-01', '2026-05-01'),
    ('audit_entries',       'audit_entries_2026_05',       '2026-05-01', '2026-06-01'),
    ('ops_timeline_events', 'ops_timeline_events_2026_03', '2026-03-01', '2026-04-01'),
    ('ops_timeline_events', 'ops_timeline_events_2026_04', '2026-04-01', '2026-05-01'),
    ('ops_timeline_events', 'ops_timeline_events_2026_05', '2026-05-01', '2026-06-01')
ON CONFLICT (parent_table, partition_name) DO NOTHING;


-- =============================================================================
-- SECTION 3: ROW-LEVEL SECURITY (TENANT ISOLATION)
-- =============================================================================
--
-- How the session context is established (RlsTenantContextConfigurer):
--
--   At the start of every merchant-scoped @Transactional method:
--     SET LOCAL app.current_merchant_id = '42';
--
--   SET LOCAL means the variable resets automatically when the transaction
--   ends (commit or rollback) — no risk of stale merchant ID leaking across
--   pooled connections.
--
-- How background jobs / admin operations bypass RLS:
--   1. Assign BYPASSRLS to the admin/migration DB role.
--   2. Or set app.current_merchant_id = '' before the query and rely on the
--      NULLIF guard below returning NULL, which makes the policy evaluate to
--      FALSE (no rows match), effectively showing 0 rows — safe default.
--   3. Or iterate per-merchant and call applyMerchantContext(merchantId) once
--      per merchant.
--
-- FORCE ROW LEVEL SECURITY: ensures even the table OWNER obeys the policy
-- when connected as a regular session (not a superuser/BYPASSRLS role).
--
-- NOTE: The migration role must hold BYPASSRLS for these ALTER TABLE statements
-- to succeed and for Flyway to see all rows during schema validation.

-- 3a. customers
ALTER TABLE customers ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS rls_customers_merchant_isolation ON customers;
CREATE POLICY rls_customers_merchant_isolation ON customers
    AS PERMISSIVE
    FOR ALL
    TO PUBLIC
    USING (
        merchant_id = NULLIF(current_setting('app.current_merchant_id', true), '')::BIGINT
    )
    WITH CHECK (
        merchant_id = NULLIF(current_setting('app.current_merchant_id', true), '')::BIGINT
    );

-- 3b. subscriptions_v2
ALTER TABLE subscriptions_v2 ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscriptions_v2 FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS rls_subscriptions_v2_merchant_isolation ON subscriptions_v2;
CREATE POLICY rls_subscriptions_v2_merchant_isolation ON subscriptions_v2
    AS PERMISSIVE
    FOR ALL
    TO PUBLIC
    USING (
        merchant_id = NULLIF(current_setting('app.current_merchant_id', true), '')::BIGINT
    )
    WITH CHECK (
        merchant_id = NULLIF(current_setting('app.current_merchant_id', true), '')::BIGINT
    );

-- 3c. invoices
ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoices FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS rls_invoices_merchant_isolation ON invoices;
CREATE POLICY rls_invoices_merchant_isolation ON invoices
    AS PERMISSIVE
    FOR ALL
    TO PUBLIC
    USING (
        merchant_id = NULLIF(current_setting('app.current_merchant_id', true), '')::BIGINT
    )
    WITH CHECK (
        merchant_id = NULLIF(current_setting('app.current_merchant_id', true), '')::BIGINT
    );

-- 3d. payment_intents_v2
ALTER TABLE payment_intents_v2 ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_intents_v2 FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS rls_payment_intents_v2_merchant_isolation ON payment_intents_v2;
CREATE POLICY rls_payment_intents_v2_merchant_isolation ON payment_intents_v2
    AS PERMISSIVE
    FOR ALL
    TO PUBLIC
    USING (
        merchant_id = NULLIF(current_setting('app.current_merchant_id', true), '')::BIGINT
    )
    WITH CHECK (
        merchant_id = NULLIF(current_setting('app.current_merchant_id', true), '')::BIGINT
    );

-- Grant the application role permission to set the session variable at runtime.
-- Replace 'firstclub_app' with the actual application role when deploying.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'firstclub_app') THEN
        EXECUTE 'GRANT SET ON PARAMETER app.current_merchant_id TO firstclub_app';
    END IF;
END $$;
