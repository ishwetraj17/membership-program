# Performance Bottlenecks

This document identifies the known performance bottlenecks in the current system, explains why each matters, and lists the mitigation already in place and the planned relief.

---

## The Honest Picture

This is a **modular monolith on a single JVM with a single PostgreSQL**. For the use case it is designed for (multi-merchant SaaS billing, hundreds to low thousands of transactions per minute), the current architecture is sufficient. The bottlenecks below become relevant at growth stages beyond that.

---

## Bottleneck 1: `ledger_lines` Insert Volume

**Why it matters:** Every financial event writes at least 2 rows to `ledger_lines` (one DEBIT, one CREDIT). At scale, a high-volume merchant generates:
- 2 rows per subscription creation
- 4 rows per payment capture (one payment entry + one invoice settlement entry)
- 2–4 rows per refund
- 2 rows per revenue recognition posting (daily, per active subscription)

A merchant with 10,000 active subscriptions generates **≥20,000 ledger_lines rows per day** from recognition alone.

**Current state:** Sequential writes in the ledger service on the hot path. No partitioning.

**Already mitigated by:**
- `ledger_entries.business_fingerprint` UNIQUE constraint prevents duplicates (avoids reprocessing)
- REQUIRES_NEW per recognition row limits TX size for nightly job

**Planned relief:**
- Partition `ledger_lines` by `created_at` month
- Ledger snapshots prevent scanning full `ledger_lines` history for balance queries
- Ledger write path will gain a Redis optimistic cache for hot account balances

**Threshold:** Begin monitoring when `ledger_lines` exceeds 5M rows.

---

## Bottleneck 2: `revenue_recognition_schedules` Nightly Job

**Why it matters:** The nightly revenue recognition job processes every `PENDING` schedule row across all merchants. At scale:
- 10,000 active subscriptions × 30-day plans = 300,000 PENDING rows at any time
- Each row: acquire REQUIRES_NEW TX → check status → post ledger entry → update status
- Sequential processing, no parallelism

**Current state:** `@Scheduled(cron = "0 30 1 * * *")` — runs at 01:30, single thread.

**Already mitigated by:**
- REQUIRES_NEW isolation: one row failure doesn't block others
- JobLock prevents duplicate concurrent runs

**Planned relief:**
- Parallel batch processing using Spring Batch (chunk-oriented processing)
- Partition `revenue_recognition_schedules` by `recognition_date` month
- Redis lock per merchant batch to allow merchant-level parallelism

**Threshold:** Begin monitoring when daily recognition volume exceeds 50,000 rows.

---

## Bottleneck 3: Outbox Poller Loop

**Why it matters:** The outbox poller runs continuously, scanning `outbox_events WHERE status='PENDING'`. Under high volume:
- Many PENDING rows → full table scan if index is not used
- Single-JVM poller → cannot fan out across multiple application instances

**Current state:**
```sql
SELECT * FROM outbox_events
WHERE status = 'PENDING'
ORDER BY created_at ASC
LIMIT :batchSize
FOR UPDATE SKIP LOCKED
```

**Already mitigated by:**
- `FOR UPDATE SKIP LOCKED` prevents duplicate processing across concurrent pollers
- Batch processing (not row-by-row)
- Index on `(status, created_at)` assumed from Flyway migration

**Planned relief:**
- In multi-instance deployment: `FOR UPDATE SKIP LOCKED` naturally load-balances across JVMs
- Kafka replaces outbox at scale (direct event bus instead of DB polling)
- Redis marker per event prevents duplicate delivery attempt polling

**Threshold:** Monitor outbox lag. Alert when lag exceeds 2 minutes. Consider Kafka when lag becomes structural (not just spike-driven).

---

## Bottleneck 4: `reconciliation` Full Table Scans

**Why it matters:** The reconciliation job runs a series of correlated subqueries and aggregations across `invoices_v2`, `payment_intents_v2`, `payment_transactions_v2`, and `ledger_lines`. Under full load, these are large table joins.

**Current queries (simplified):**
```sql
-- Layer 1: Payments without matching invoices
SELECT pi.* FROM payment_intents_v2 pi
WHERE NOT EXISTS (SELECT 1 FROM invoices_v2 iv WHERE iv.id = pi.invoice_id)

-- Layer 2: Payment vs ledger cross-check
SELECT p.id, SUM(p.captured_amount) - SUM(ll.amount)
FROM payment_intents_v2 p
JOIN ledger_lines ll ON ...
WHERE batch_date = :date
```

**Planned relief:**
- Run reconciliation on a read replica (isolates from write traffic)
- Partition by `merchant_id` and run per-merchant batches with merchant-level parallelism
- Reconciliation result caching: only re-process changed merchants

**Threshold:** Monitor recon job duration. Alert when it exceeds 20 minutes.

---

## Bottleneck 5: `subscriptions_v2` Optimistic Lock Contention

**Why it matters:** Every write to subscriptions uses `@Version` optimistic locking. Under concurrent load (dunning retrying + webhook updating + API updating same subscription), the retry rate for 409 conflicts increases.

**Current state:** Optimistic locking with client-side retry.

**Already mitigated by:**
- Clear ownership: only the dunning job and cancellation paths modify status concurrently
- Idempotency key on the mutation endpoint
- Unique constraint on active subscription per customer

**Planned relief:**
- Redis lock per subscription (1-second TTL) to prevent concurrent modification before the DB TX
- Key: `{env}:firstclub:sub:lock:{subscriptionId}`

---

## Bottleneck 6: `idempotency_keys` Table Growth

**Why it matters:** Every mutating API request inserts a row. High API volume creates a large table.

**Already mitigated by:**
- Nightly cleanup job: `DELETE WHERE expires_at < NOW()`
- Default TTL: 24 hours

**Threshold:** Monitor table size. Partition by `expires_at` week if > 1M rows.

---

## Bottleneck 7: Subscription Renewal Batch Window

**Why it matters:** All subscriptions due for renewal on a given day trigger dunning and invoice creation at the same cron tick. A large merchant with thousands of monthly-renewing subscriptions creates a burst of writes in a narrow window.

**Already mitigated by:**
- `@Scheduled` runs nightly; writes spread across the night job window
- Per-subscription JobLock prevents duplicate dunning

**Planned relief:**
- Spread renewal load across a 4-hour window by partitioning subscriptions into buckets by `subscription_id % N`
- Process each bucket in sequence

---

## Summary: Bottleneck Prioritization

| Bottleneck | Current Risk | Action Threshold | Planned Fix |
|---|---|---|---|
| `ledger_lines` growth | Medium | 5M rows | Table partitioning + snapshots |
| Revenue recognition nightly job | Medium | 50K daily rows | Spring Batch parallelism |
| Outbox poller lag | Low today | 2-min lag structural | Kafka |
| Recon full table scans | Low today | 20-min job duration | Read replica + per-merchant batches |
| Optimistic lock contention | Low today | >1% 409 rate | Redis subscription locks |
| Idempotency key cleanup | Low today | 1M rows | Partitioned cleanup |
| Renewal burst window | Low today | 10K simultaneous renewals | Bucketed scheduling |


---

## Phase 20 — Operational Finalization: Bottleneck Observability

### New Counters Available via GET /api/v2/admin/system/summary

| Counter | Field | Alert Threshold |
|---------|-------|----------------|
| Dunning backlog | `dunningBacklogCount` | > 500 SCHEDULED attempts |
| Integrity violations | `integrityViolationCount` | Any value > 0 |
| Stale job locks | `staleJobLockCount` | > 2 (indicates scheduler crash) |
| Outbox failed | `outboxFailedCount` | > 0 (indicates handler regression) |
| DLQ depth | `dlqCount` | > 10 (indicates repeated handler failures) |

### Bottleneck Indicators by Subsystem

- **PostgreSQL write node**: single point of serialization; monitor `outboxPendingCount` growth rate
- **Outbox poller**: single-threaded dispatcher; throughput bounded by polling interval (default 5 s)
- **Webhook fan-out**: synchronous per-merchant delivery; degraded under > 1 K merchants
- **Quartz scheduler**: cannot distribute execution; `staleJobLockCount > 0` means a job is stuck
- **Redis single instance**: SPOF for rate-limiting and idempotency; monitor `redisStatus` in deep health

### Response Protocol

When `integrityViolationCount > 0`, the deep health endpoint returns `DEGRADED`.
Trigger the integrity check review workflow in `01-reconciliation-playbook.md`.
