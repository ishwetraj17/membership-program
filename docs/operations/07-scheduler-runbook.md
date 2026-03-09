# Scheduler Operations Runbook — Phase 7

**Tier**: Platform Infrastructure  
**Owner**: Platform / Backend  
**AlertGroup**: `scheduler-health`

---

## Overview

Phase 7 introduces a durable, observable scheduler infrastructure for singleton batch processors.  
Key capabilities:

| Capability | Mechanism |
|---|---|
| Singleton execution guard | `pg_try_advisory_xact_lock` (transaction-scoped) |
| Primary-node-only execution | `SELECT NOT pg_is_in_recovery()` |
| Durable execution history | `scheduler_execution_history` table |
| Health / staleness monitoring | `SchedulerHealthMonitor` → `/api/v2/admin/schedulers/health` |
| Crash / stuck-RUNNING detection | `/api/v2/admin/schedulers/stale-running` |

---

## 1. Advisory Locks vs. Distributed Locks

| Dimension | PG Advisory Locks | Redis Distributed Locks |
|---|---|---|
| **Dependency** | Already in PostgreSQL (zero extra infra) | Requires Redis cluster |
| **Scope** | Session-level or transaction-scoped | TTL-based (wall-clock) |
| **Failure mode** | Released automatically on tx commit/rollback | Must handle TTL expiry; fence tokens required |
| **Use case** | Singleton cron / batch within a single DB cluster | Cross-service distributed coordination |
| **Phase 7 choice** | ✅ `pg_try_advisory_xact_lock` preferred for batch MX | Phase 6 Redis locks for cross-service scenarios |

### Why transaction-scoped (`xact`) rather than session-level?

- A `pg_try_advisory_xact_lock` is **automatically released** when the enclosing transaction commits or rolls back.
- Session-level locks (`pg_try_advisory_lock`) survive transaction boundaries and require an explicit `pg_advisory_unlock` call.  
  If a scheduler crash kills the JVM before `releaseSessionLock()` runs, the lock stays held until the database connection is closed, potentially starving subsequent scheduler runs.
- For batch-style schedulers wrapped in a single `@Transactional` method, the xact variant is the correct primitives.

---

## 2. When a Scheduler Should Skip Execution

A scheduler skips (writes a `SKIPPED` record) under two conditions:

### 2a. Advisory lock busy (another instance is running)

```java
if (!lockService.tryAcquireForBatch(schedulerName)) {
    recorder.recordSkipped(schedulerName, "lock-busy");
    return;
}
```

**Expected outcome**: Only the first JVM to win `pg_try_advisory_xact_lock(lockId)` proceeds. All other replicas skip with `lock-busy`. Lock is released when the winner's transaction completes.

### 2b. Not the primary node

```java
if (!guard.canRunScheduler(schedulerName)) {
    recorder.recordSkipped(schedulerName, "not-primary");
    return;
}
```

**Expected outcome**: Read replicas (`pg_is_in_recovery() = true`) return `false` from `DatabaseRoleChecker.isPrimary()` and skip.

**Fail-safe**: If `isPrimary()` throws an exception the method returns `false`, so unknown nodes skip rather than risk write conflicts.

### Changing primary-only behaviour

```properties
# application.properties
scheduler.primary-only.enabled=true   # default — only primary runs schedulers
scheduler.primary-only.enabled=false  # allow any node (e.g. single-node dev)
```

---

## 3. Stale Scheduler Detection

### Algorithm

```
checkHealth(name, expectedInterval):
  lastSuccess = findLatestSuccessBySchedulerName(name)
  if absent     → NEVER_RAN
  if lastSuccess.completedAt < now - expectedInterval → STALE
  else → HEALTHY
```

### API endpoint

```
GET /api/v2/admin/schedulers/health?expectedIntervalHours=25
Authorization: Bearer <admin-token>
```

**Sample response:**

```json
[
  {
    "schedulerName": "subscription-renewal",
    "health": "HEALTHY",
    "lastSuccessAt": "2025-01-10T03:00:12Z",
    "lastRunAt":    "2025-01-10T03:00:05Z",
    "lastRunStatus": "SUCCESS",
    "expectedIntervalHours": 25
  },
  {
    "schedulerName": "dunning-daily",
    "health": "STALE",
    "lastSuccessAt": "2025-01-08T03:00:15Z",
    "expectedIntervalHours": 25,
    "diagnosis": "Last success was 2 days ago; expected every 25 h"
  }
]
```

### Tuning the interval

Set `expectedIntervalHours` to a value slightly above the scheduler's actual cron period to absorb minor delays.  For a daily scheduler (every 24 h), use `25` — giving a 1-hour buffer before alerting.

---

## 4. Crash / Stuck-RUNNING Detection

A `RUNNING` record that is never updated to `SUCCESS` or `FAILED` is a symptom of a hard crash (OOM, `kill -9`, network partition during write).

### API endpoint

```
GET /api/v2/admin/schedulers/stale-running?staleMinutes=30
Authorization: Bearer <admin-token>
```

Returns all `scheduler_execution_history` rows where:
- `status = 'RUNNING'`
- `started_at < NOW() - interval`

**Remediation**:

1. Verify the node that owns the stale run is no longer live (`nodeId` column).
2. Manually update the record in the DB if needed:
   ```sql
   UPDATE scheduler_execution_history
   SET    status       = 'FAILED',
          completed_at = NOW(),
          error_message = 'manually-closed: node crashed'
   WHERE  id = <run_id>;
   ```
3. Investigate the node via container / cloud logs for root cause.
4. If the scheduler is `NEVER_RAN` afterwards, trigger a manual run or wait for the next scheduled period.

---

## 5. Node ID Tracking

Each execution record stores the `nodeId` from `LockOwnerIdentityProvider.getInstanceId()`:

```
Format: <hostname>-<first-8-chars-of-random-UUID>
Example: api-server-3-a1b2c3d4
```

Use the `nodeId` to correlate scheduler issues with specific pod/container logs in your observability stack.

---

## 6. `pg_is_in_recovery()` Internals

| Cluster state | `pg_is_in_recovery()` | `DatabaseRoleChecker.isPrimary()` | Scheduler runs? |
|---|---|---|---|
| Primary (writable) | `false` | `true` | ✅ Yes |
| Hot standby / replica | `true` | `false` | ❌ No (skips) |
| Exception / unreachable | N/A | `false` (fail-safe) | ❌ No (skips) |

> **Warning**: If your PostgreSQL setup uses synchronous replicas with a Patroni switchover, the new primary will pass `isPrimary()` on its very next scheduler tick. There is no additional fencing mechanism — the advisory lock ensures at-most-once execution on the winning primary.

---

## 7. Scheduler History Query Reference

### Recent executions for a specific scheduler

```
GET /api/v2/admin/schedulers/history?schedulerName=subscription-renewal&windowHours=48&limit=20
```

### All executions in the last 24 hours (all schedulers)

```
GET /api/v2/admin/schedulers/history?windowHours=24&limit=100
```

### Direct SQL

```sql
-- Last 10 runs for one scheduler
SELECT id, scheduler_name, node_id, started_at, completed_at, status, processed_count, error_message
FROM   scheduler_execution_history
WHERE  scheduler_name = 'subscription-renewal'
ORDER BY started_at DESC
LIMIT  10;

-- All FAILED runs in the last 7 days
SELECT *
FROM   scheduler_execution_history
WHERE  status     = 'FAILED'
  AND  created_at > NOW() - INTERVAL '7 days'
ORDER BY created_at DESC;

-- Scheduler execution frequency
SELECT scheduler_name, COUNT(*) AS runs,
       COUNT(*) FILTER (WHERE status = 'SUCCESS') AS successes,
       COUNT(*) FILTER (WHERE status = 'FAILED')  AS failures,
       COUNT(*) FILTER (WHERE status = 'SKIPPED') AS skips
FROM   scheduler_execution_history
WHERE  created_at > NOW() - INTERVAL '7 days'
GROUP BY scheduler_name
ORDER BY runs DESC;
```

---

## 8. Alerting Recommendations

| Alert | Condition | Severity |
|---|---|---|
| Scheduler STALE | `health == STALE` for any scheduler | `HIGH` |
| Scheduler NEVER_RAN | `health == NEVER_RAN` after expected first-run window | `MEDIUM` |
| Stuck RUNNING | stale-running endpoint returns any records > 30 min | `HIGH` |
| High SKIPPED rate | > 90% of executions are SKIPPED for the last hour | `MEDIUM` (possible primary flip) |
| All nodes NOT primary | All nodes report `isPrimary() = false` | `CRITICAL` (split-brain) |

---

## 9. Migration Reference

- **V53** (`V53__scheduler_execution_tracking.sql`): Creates `scheduler_execution_history` table.  
  This migration is idempotent through Flyway versioning. Do not re-run manually.
