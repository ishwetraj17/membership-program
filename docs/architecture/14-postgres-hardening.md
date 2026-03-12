# 14 — PostgreSQL Hardening: Partial Indexes, Partitioning, and Row-Level Security

**Phase**: 22  
**Migration**: `V67__db_hardening_indexes_partitioning_rls.sql`  
**New packages**: `com.firstclub.platform.db`, `com.firstclub.platform.db.partitioning`, `com.firstclub.platform.db.rls`

---

## Overview

Phase 22 pushes correctness and performance guarantees down into the database itself, making them independent of application-layer behaviour. The three pillars are:

1. **Partial indexes** — narrow index footprint to only the rows that hot queries actually touch.
2. **Partition management** — register the four high-volume append-only tables as partition candidates and automate child-table pre-creation.
3. **Row-Level Security (RLS)** — enforce tenant isolation at the storage engine so cross-merchant data leakage is impossible even if the service layer has a bug.

---

## 1. Partial Indexes

### Why partial indexes matter

A full B-tree index on `status` stores every row, including completed payments, processed outbox events, and recognised revenue that will never be queried again. A partial index stores only the rows that satisfy the `WHERE` clause — typically 1–5 % of total table size for "active work" predicates. Benefits:

- **Smaller index** → fits in buffer pool → fewer I/O reads.
- **Faster scans** → planner can identify the matching rows with a smaller tree.
- **Reduced write amplification** → rows that don't satisfy the predicate cause no index updates on insert/update.

### Indexes added in V67

| Index name | Table | Columns | Filter | Query pattern |
|---|---|---|---|---|
| `idx_outbox_retryable_by_merchant` | `outbox_events` | `(merchant_id, next_attempt_at)` | `status IN ('NEW','FAILED')` | Per-merchant outbox dispatch |
| `idx_subscriptions_v2_active_renewal` | `subscriptions_v2` | `(merchant_id, next_billing_at)` | `status = 'ACTIVE'` | Renewal scheduler |
| `idx_invoices_unpaid_by_merchant` | `invoices` | `(merchant_id, due_date)` | `status IN ('OPEN','PAST_DUE')` | Collections service |
| `idx_pi_v2_succeeded_by_merchant` | `payment_intents_v2` | `(merchant_id, created_at DESC)` | `status = 'SUCCEEDED'` | Reconciliation / reporting |
| `idx_domain_events_causation` | `domain_events` | `(causation_id)` | `causation_id IS NOT NULL` | Saga / process-manager tracing |
| `idx_rr_schedules_pending_merchant` | `revenue_recognition_schedules` | `(merchant_id, recognition_date)` | `status = 'PENDING'` | Per-merchant RR runner |

**Note on `CONCURRENTLY`**: Flyway wraps every migration in a transaction and `CREATE INDEX CONCURRENTLY` cannot run inside a transaction. All indexes in V67 use plain `CREATE INDEX IF NOT EXISTS`. On a live database requiring zero-downtime migration, these should be created manually with `CONCURRENTLY` before applying the Flyway baseline.

### Verifying index usage

Use `DbConstraintExplainer` to confirm the planner chose the expected index:

```java
String plan = dbConstraintExplainer.explainQuery(
    "SELECT id, payload FROM outbox_events " +
    "WHERE merchant_id = 1 AND status IN ('NEW','FAILED') " +
    "ORDER BY next_attempt_at LIMIT 100");

assertThat(dbConstraintExplainer.isIndexUsed(
    plan, "idx_outbox_retryable_by_merchant")).isTrue();
```

`DbConstraintExplainer.listPartialIndexes(tableName)` queries `pg_indexes` to enumerate the partial indexes on any table.

---

## 2. Partitioning Readiness

### Why partitioning matters

The four high-volume append-only tables — `outbox_events`, `domain_events`, `audit_entries`, `ops_timeline_events` — grow without bound in naive deployments. Without partitioning, purging old data requires a full table `DELETE` with a sequential scan, which:

- Blocks autovacuum from reclaiming space efficiently.
- Creates large write-ahead log (WAL) traffic.
- Holds table locks that compete with ongoing inserts.

With `PARTITION BY RANGE (created_at)`, each calendar month lives in its own child table. Dropping a month's data is `ALTER TABLE ... DETACH PARTITION` + `DROP TABLE` — both O(1) catalog operations that take milliseconds regardless of row count.

Additional benefit: the planner prunes partitions that cannot contribute to a query. A query for `created_at BETWEEN '2026-03-01' AND '2026-03-31'` touches only the March 2026 child table.

### Architecture

```
partition_management_log            ← advisory registry of created partitions
    id, parent_table, partition_name,
    partition_start, partition_end, status

create_monthly_partition(           ← PL/pgSQL helper (V67)
    p_parent_table TEXT,
    p_year INT, p_month INT)
    RETURNS TEXT

PartitionManager                    ← Spring @Component
    ensureAllManagedPartitions(int) ← called at startup + scheduled
    ensurePartitionsExist(table, n) ← creates months [0..n] from now
    createPartition(table, YearMonth) ← delegates to SQL function
    recordPartition(...)            ← INSERT INTO partition_management_log

DbMaintenanceService                ← @Scheduled cron "0 0 1 1 * *"
    ensureUpcomingPartitions()      ← ensureAllManagedPartitions(2)
```

### Managed tables

The four tables targeted by the partitioning strategy are declared as constants in `PartitionManager.MANAGED_TABLES`. Converting an existing unpartitioned table to `PARTITION BY RANGE` requires a maintenance window (create a new partitioned table, migrate data, rename). The `PartitionManager` is designed to operate on already-converted tables and is deliberately independent of the conversion step.

### Partition naming

Child partitions follow the pattern `<parent_table>_YYYY_MM`:

```
outbox_events_2026_03
outbox_events_2026_04
domain_events_2026_03
...
```

`PartitionManager.resolvePartitionName(String, YearMonth)` is the single source of truth for this naming scheme.

### H2 / non-PostgreSQL

All partition operations silently skip on H2. The `isPostgres()` check inside each operation returns `false` on H2, so `PartitionManager` is safe to wire into tests and the dev profile without any additional guards.

---

## 3. Row-Level Security

### Defense-in-depth rationale

Service-layer tenant isolation (passing `merchantId` through repository queries) is the primary control. RLS is the secondary control that activates if the primary fails:

- A bug passes the wrong `merchantId` to a repository method.
- A new developer writes a query that forgets the merchant filter.
- A dependency injection error causes a wrong service bean to process a request.

With RLS enabled, PostgreSQL itself rewrites every `SELECT`, `INSERT`, `UPDATE`, and `DELETE` to include the tenant predicate, regardless of what SQL the application sends. Cross-merchant data is structurally invisible at the engine level.

### Policy design

The four most sensitive tables receive identical policies in V67:
`customers`, `subscriptions_v2`, `invoices`, `payment_intents_v2`.

```sql
CREATE POLICY rls_<table>_merchant_isolation ON <table>
    AS PERMISSIVE FOR ALL TO PUBLIC
    USING (
        merchant_id = NULLIF(current_setting('app.current_merchant_id', true), '')::BIGINT
    )
    WITH CHECK (
        merchant_id = NULLIF(current_setting('app.current_merchant_id', true), '')::BIGINT
    );
```

`NULLIF(current_setting(..., true), '')::BIGINT`:
- `current_setting('app.current_merchant_id', true)` — the `true` flag returns `NULL` instead of throwing when the variable is not set (as opposed to raise an error).
- `NULLIF(..., '')` — converts the empty-string sentinel (set by `clearMerchantContext()`) to `NULL`.
- `::BIGINT` cast — causes the whole expression to return `NULL` when either input is empty/unset.
- A `NULL` in `USING` evaluates as `false` → **zero rows are visible** when no merchant context is set. This is the safe default — background jobs that legitimately access all merchants must use explicit context, not rely on an open policy.

`FORCE ROW LEVEL SECURITY` ensures the table owner (the application migration role) also obeys the policy during normal sessions. This prevents accidental policy bypass via role escalation.

### Setting the session variable

`RlsTenantContextConfigurer` applies the session variable using `SET LOCAL`:

```java
// Inside a @Transactional method:
rlsTenantContextConfigurer.applyMerchantContext(merchantId);
// All JPA/JDBC queries in this transaction are now scoped to merchantId.
```

`SET LOCAL` scopes the variable to the current transaction. When the transaction commits or rolls back, the variable automatically resets to its previous value. There is **no risk** of a stale merchant ID leaking to the next request that reuses the same pooled connection.

### Background jobs and cross-merchant access

Background jobs that legitimately read data across all merchants have two options:

**Option A — BYPASSRLS role** (recommended for trusted internal jobs):
```sql
GRANT BYPASSRLS ON ROLE background_worker;
```
The job connects as `background_worker` and the RLS policies are not applied.

**Option B — Per-merchant iteration** (for jobs that already process per merchant):
```java
@Transactional
public void processForMerchant(Long merchantId) {
    rlsTenantContextConfigurer.applyMerchantContext(merchantId);
    // ... all queries see only this merchant's rows
}
```

### Verifying RLS is active

```java
assertThat(dbConstraintExplainer.isRlsEnabled("customers")).isTrue();
assertThat(dbConstraintExplainer.isRlsEnabled("subscriptions_v2")).isTrue();
assertThat(dbConstraintExplainer.isRlsEnabled("invoices")).isTrue();
assertThat(dbConstraintExplainer.isRlsEnabled("payment_intents_v2")).isTrue();
```

---

## 4. Component Reference

| Component | Package | Responsibility |
|---|---|---|
| `PartitionManager` | `com.firstclub.platform.db.partitioning` | Creates monthly child partitions via PL/pgSQL function; records state in `partition_management_log` |
| `RlsTenantContextConfigurer` | `com.firstclub.platform.db.rls` | `SET LOCAL app.current_merchant_id` for current transaction; clear context; read current context |
| `DbConstraintExplainer` | `com.firstclub.platform.db` | `EXPLAIN` plan inspection; `pg_indexes` partial index listing; `pg_tables` RLS status; `pg_inherits` partition listing |
| `DbMaintenanceService` | `com.firstclub.platform.db` | Scheduled (1st of month, 01:00) partition pre-creation; programmatic maintenance trigger |

---

## 5. Operational Runbook

### Check which partitions exist
```java
List<String> outboxPartitions = dbConstraintExplainer.listPartitions("outbox_events");
```
Or directly:
```sql
SELECT c.relname, p.relname AS parent
FROM   pg_inherits i
JOIN   pg_class c ON c.oid = i.inhrelid
JOIN   pg_class p ON p.oid = i.inhparent
WHERE  p.relname = 'outbox_events'
ORDER  BY c.relname;
```

### Manually pre-create next 3 months of partitions
```java
dbMaintenanceService.runPartitionMaintenance(3);
```

### Check management log
```sql
SELECT parent_table, partition_name, partition_start, partition_end, status, created_at
FROM   partition_management_log
ORDER  BY parent_table, partition_start;
```

### Verify partial indexes on a table
```sql
SELECT indexname, indexdef
FROM   pg_indexes
WHERE  tablename = 'outbox_events'
  AND  indexdef LIKE '%WHERE%';
```

### Check RLS policies
```sql
SELECT tablename, policyname, cmd, qual
FROM   pg_policies
WHERE  tablename IN ('customers', 'subscriptions_v2', 'invoices', 'payment_intents_v2')
ORDER  BY tablename;
```
