# Ops Runbook

Operational reference for the FirstClub Membership Platform. Use this document during incidents and routine operations.

---

## Table of Contents

1. [DLQ Handling](#1-dlq-handling)
2. [Outbox Lag Interpretation](#2-outbox-lag-interpretation)
3. [Deep Health Interpretation](#3-deep-health-interpretation)
4. [Scheduler Lock Guidance](#4-scheduler-lock-guidance)
5. [Incident Debugging Quick Steps](#5-incident-debugging-quick-steps)
6. [Feature Flag Operations](#6-feature-flag-operations)

---

## 1. DLQ Handling

### What is the DLQ?

The `dead_letter_messages` table accumulates messages that failed processing and could not be retried successfully. Sources include:

| Source constant | Meaning |
|---|---|
| `WEBHOOK` | Inbound webhook from payment gateway that failed to process |
| `OUTBOX` | An outbox event that a handler threw an unrecoverable exception for |
| `PAYMENT_SUCCEEDED` / etc. | Source mirrors the original event type for easy handler matching |

### Viewing DLQ entries

```http
GET /api/v2/admin/system/dlq
Authorization: Bearer <admin-token>
```

Each entry contains `id`, `source`, `payload` (full JSON), `error` (exception message), and `createdAt`.

### Retrying a DLQ entry

```http
POST /api/v2/admin/system/dlq/{id}/retry
Authorization: Bearer <admin-token>
```

**What happens internally:**
1. A new `OutboxEvent` is inserted with `status=NEW`, `eventType=source`, `payload` copied from the DLQ entry.
2. The DLQ row is deleted.
3. The `OutboxPoller` picks up the requeued event on its next cycle.

**Before retrying, confirm:**
- [ ] The underlying failure cause is fixed (e.g., missing handler registration, downstream service up).
- [ ] The payload is not a poison message — if the same message fails 3+ times, do not retry; investigate the root cause.
- [ ] Handler for `eventType = source` is registered in `OutboxEventHandlerRegistry`.

### Poison message safeguard

The `OutboxEvent.attempts` counter is set to `0` on retry. If the requeued event reaches the handler's `MAX_ATTEMPTS` threshold again, the poller will stop retrying it (it transitions to `FAILED` status). The message will **not** be re-DLQ'd automatically unless the handler logic explicitly moves it there.

---

## 2. Outbox Lag Interpretation

### Checking outbox lag

```http
GET /api/v2/admin/system/outbox/lag
Authorization: Bearer <admin-token>
```

### Response fields

| Field | Interpretation |
|---|---|
| `newCount` | Events waiting to be picked up by the poller — should drain quickly |
| `processingCount` | Events mid-flight — should be zero between poller runs |
| `failedCount` | Events that exhausted retries — require DLQ inspection or manual fix |
| `doneCount` | Completed events — informational only |
| `totalPending` | `newCount + processingCount + failedCount` — primary lag indicator |
| `byEventType` | Per-type breakdown of non-DONE events — use to identify hotspots |

### Alert thresholds (suggested)

| Metric | Warning | Critical |
|---|---|---|
| `totalPending` | > 100 | > 500 |
| `failedCount` | > 0 | > 10 |
| `processingCount` | > 50 (stuck?) | > 100 |

### Common causes of lag

- **Poller disabled or crashed** — check `OutboxPoller` scheduler thread, `@Scheduled` health.
- **Handler throwing consistently** — events accumulate in `FAILED`; check application logs for handler exceptions.
- **DB lock contention** — the poller uses `FOR UPDATE SKIP LOCKED`; high lock contention on `outbox_events` table can slow throughput.

---

## 3. Deep Health Interpretation

### Getting the deep health report

```http
GET /api/v2/admin/system/health/deep
Authorization: Bearer <admin-token>
```

### Overall status values

| Status | Meaning |
|---|---|
| `HEALTHY` | All counters nominal — no action required |
| `DEGRADED` | At least one backlog or failure counter is non-zero — investigate |
| `DOWN` | Database was unreachable during check — critical incident |

### Field-by-field guide

| Field | Healthy value | Degraded indicator |
|---|---|---|
| `dbReachable` | `true` | `false` → DB connectivity issue |
| `outboxPendingCount` | Low (< 50) | Growing over time → poller issue |
| `outboxFailedCount` | `0` | > 0 → handler failures in outbox |
| `dlqCount` | `0` | > 0 → unprocessed dead letters |
| `webhookPendingCount` | Low | Sustained high → dispatcher issue |
| `webhookFailedCount` | `0` | > 0 → webhook delivery failing |
| `revRecogFailedCount` | `0` | > 0 → revenue recognition posting errors |
| `reconMismatchOpenCount` | `0` | > 0 → unresolved reconciliation gaps |
| `featureFlagCount` | Informational | — |
| `ledgerStatus` | `UNCHECKED` | Check via `GET /api/v2/ledger` balance invariant |

> **Note on `ledgerStatus`:** The deep health endpoint does not currently run the ledger double-entry balance check inline (it would be too expensive to run on every health poll). Instead, monitor via the ledger balance snapshot endpoint and set up an off-cycle job that checks `SUM(debit_lines) = SUM(credit_lines)` per entry.

---

## 4. Scheduler Lock Guidance

### Why lock schedulers?

In multi-pod deployments, all pods run the same `@Scheduled` jobs simultaneously. Without coordination, multiple pods may pick up the same renewal batch or recon run concurrently, causing duplicate processing.

### Using `JobLockService`

```java
@Scheduled(cron = "0 5 1 * * *")
public void processRenewals() {
    String owner = InetAddress.getLocalHost().getHostName() + ":" + PID;
    LocalDateTime until = LocalDateTime.now().plusMinutes(10);
    if (!jobLockService.acquireLock("RENEWAL_JOB", owner, until)) {
        log.info("Renewal job skipped — lock held by another pod");
        return;
    }
    try {
        renewalService.processRenewals();
    } finally {
        jobLockService.releaseLock("RENEWAL_JOB", owner);
    }
}
```

### Jobs that should be coordinated

| Job class | Suggested lock name |
|---|---|
| `RenewalScheduler` | `RENEWAL_JOB` |
| `DunningSchedulerV2` | `DUNNING_JOB` |
| `OutboxPoller` | `OUTBOX_POLLER` (already uses `SKIP LOCKED`) |
| `NightlyReconScheduler` | `RECON_NIGHTLY` |
| `RevenueRecognitionScheduler` | `REVENUE_RECOGNITION_JOB` |
| `DailySnapshotScheduler` | `SNAPSHOT_DAILY` |

### Migration to ShedLock

The current `JobLockService` is a lightweight DB-backed coordinator suitable for small deployments. For high-frequency jobs or more than ~5 pods, migrate to [ShedLock](https://github.com/lukas-krecan/ShedLock):

1. Add `net.javacrumbs.shedlock:shedlock-spring` and `shedlock-provider-jdbc-template` to `pom.xml`.
2. Replace `JobLock` table with ShedLock's `shedlock` table (compatible structure).
3. Annotate each scheduler method with `@SchedulerLock(name = "...", lockAtMostFor = "10m")`.
4. Drop `JobLockService` and `JobLockRepository`.

---

## 5. Incident Debugging Quick Steps

### "Payments not processing"

1. `GET /api/v2/admin/system/health/deep` — check `dbReachable`, `outboxFailedCount`, `webhookFailedCount`
2. `GET /api/v2/admin/system/outbox/lag` — look for `failedCount > 0` and which `eventType` is failing
3. Check application logs for the failing handler class
4. `GET /api/v2/admin/system/dlq` — see if poison messages are accumulating
5. Check `GET /api/v2/admin/gateway/health` — is the simulated/real gateway healthy?

### "Revenue recognition not posting"

1. `GET /api/v2/admin/system/health/deep` — check `revRecogFailedCount`
2. Query `revenue_recognition_schedules` where `status = 'FAILED'` for the error field
3. Check `RevenueRecognitionScheduler.enabled` = true in `application.properties`
4. Verify `LedgerAccount` rows exist for deferred revenue and income accounts

### "Reconciliation mismatches growing"

1. `GET /api/v2/admin/system/health/deep` — check `reconMismatchOpenCount`
2. `GET /api/v2/admin/recon/mismatches?status=OPEN` — list open mismatches
3. Confirm `NightlyReconScheduler` is running — check job lock state
4. Look for settlement batch import failures in `GET /api/v2/admin/recon/statement-imports`

### "Webhook delivery failures"

1. Check `webhookFailedCount` in deep health
2. `GET /api/v2/merchants/{id}/webhooks/deliveries` — see recent delivery attempts
3. Check endpoint URL is still reachable — look for `GAVE_UP` status
4. Re-enable auto-disabled endpoints once the issue is fixed

---

## 6. Feature Flag Operations

### Listing flags

```http
GET /api/v2/admin/system/feature-flags
```

### Enabling or disabling a flag

```http
PUT /api/v2/admin/system/feature-flags/{flagKey}
Content-Type: application/json

{ "enabled": true, "configJson": "{\"rollout\": 50}" }
```

### Standard flag keys

| Flag key | Default | Purpose |
|---|---|---|
| `GATEWAY_ROUTING` | false | Use dynamic routing rules instead of default gateway |
| `BACKUP_PAYMENT_RETRIES` | false | Enable fallback payment method on primary failure |
| `OUTBOUND_WEBHOOKS` | false | Enable merchant outbound webhook dispatch |
| `REVENUE_RECOGNITION_MODE` | false | Switch to automated revenue recognition scheduler |
| `SANDBOX_ENFORCEMENT` | true | Enforce sandbox/live mode checks on merchant API keys |

### Merchant-scoped overrides

Merchant overrides are stored as separate rows with `scope = MERCHANT` and a non-null `merchant_id`. They take precedence over the global flag. To manage merchant-specific overrides, insert/update directly in `feature_flags` with the merchant ID (admin SQL or future API endpoint).
