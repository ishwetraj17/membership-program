# Sync vs Async Architecture

This document explains which operations are synchronous (within the request-response cycle) and which are asynchronous (handled outside the request cycle), and why that distinction was made for each.

---

## Synchronous Hot Paths

These operations complete entirely within the API request/response cycle. The client receives a definitive result before the HTTP connection closes.

| Operation | Why Synchronous |
|---|---|
| Subscription creation | Customer expects immediate confirmation of enrollment; invoice must be generated before payment can begin |
| Invoice generation | Required to exist before payment can be initiated |
| Payment intent creation | Customer is actively waiting; gateway must accept the intent before the UI can proceed |
| Payment confirmation (gateway callback) | Financial state must be updated atomically; webhook retries require idempotent handling |
| Refund creation | Merchant/customer expects immediate acknowledgment; ledger entry posted in same transaction |
| Dispute opening | Financial reserve must be set immediately to protect against double-spending |
| Risk checks | Must block the write path; an async risk check would allow a payment to proceed before the block decision |
| Idempotency enforcement | Must be synchronous by definition — protecting against duplicate effects |

---

## Asynchronous Paths

These operations happen after the primary transaction commits. They may have a delay, and failures in them do not roll back the triggering operation.

### Outbox → Events Publication

```
Request               DB Transaction             Outbox Poller (async)
  │                        │                           │
  ├─►[Write business state]│                           │
  ├─►[Write outbox event]  │                           │
  └──────── COMMIT ─────────┘                           │
                                          [polls domain_events_outbox]
                                          [marks event PROCESSED]
                                          [inserts into domain_events]
```

**Why async?** Domain events are notifications, not guarantees. The triggering ACID transaction must not be held open waiting for downstream subscribers. If the outbox poller lags, business state is still correct.

**Lag tolerance:** Typically <5 seconds under normal load. Can be minutes during incident.

---

### Domain Events → Projection Updates

```
domain_events row inserted
    │
    ▼
ProjectionEventListener (Spring event or outbox-to-events bridge)
    │
    ▼
ProjectionUpdateService.update()   → updates subscription_projections
                                   → invalidates Redis proj cache (future)
```

**Why async?** Projections are derived read views. They may lag without affecting financial correctness. Strong consistency reads always go to source tables.

**Lag tolerance:** Acceptable to be up to 30 seconds behind for dashboard projections.

---

### Webhook Delivery

```
domain_events row inserted
    │
    ▼
WebhookDeliveryServiceImpl creates WebhookDelivery rows (one per matching endpoint)
    │
    ▼
Delivery worker polls PENDING deliveries
    │
    ▼
HTTP POST to merchant endpoint (with retry + backoff)
```

**Why async?** Merchant endpoints may be slow, down, or unreachable. Blocking the payment transaction on webhook delivery would make payment confirmation as unreliable as the merchant's endpoint.

**Retry:** Exponential backoff; up to N attempts; endpoint disabled after threshold failures.

---

### Revenue Recognition Scheduler

```
@Scheduled(cron="0 0 3 * * *")
RevenueRecognitionPostingServiceImpl.postDueRecognitionsForDate(today)
```

**Why async?** Revenue recognition is an accounting operation that runs once per day. It is inherently batch. Running it synchronously on invoice creation would slow the user-facing API.

**Failure handling:** Each schedule row commits in `REQUIRES_NEW`. Failed rows stay `PENDING` and are retried on the next run.

---

### Dunning Schedulers

```
V1: @Scheduled(cron="0 0 1 * * *")  → DunningJobV1
V2: @Scheduled(cron="0 0 1 * * *")  → DunningRetryJobV2
```

**Why async?** Dunning involves re-attempting payments for PAST_DUE subscriptions. These are batch operations that should not block the UI. The scheduler picks up all due attempts and processes them.

---

### Reconciliation Scheduler

```
@Scheduled(cron="0 10 2 * * *")  → AdvancedReconciliationScheduler
```

**Why async?** Reconciliation is a read-heavy comparison job. It runs after settlement data is available (02:00 UTC settlement job runs first). It must not block any user-facing API.

---

### Ledger Snapshot Scheduler

```
@Scheduled(cron="0 30 3 * * *")  → DailySnapshotScheduler
```

**Why async?** Snapshots are pre-computed for historical reporting. They must run after reconciliation to catch the day's last entries. Running synchronously inside any business operation would be nonsensical.

---

### Idempotency Cleanup

```
@Scheduled(cron="0 0 4 * * *")  → IdempotencyCleanupJobImpl
```

**Why async?** Expired idempotency keys are a housekeeping operation. Purging them has no business logic impact — only storage impact.

---

## Outbox Pattern Details

The outbox pattern ensures that **business state changes and event publication are atomic**, with no dual-write risk.

```
WITHOUT OUTBOX (dangerous):
  1. DB write (COMMIT)           → state updated
  2. Publish event to broker     → may fail after COMMIT
     Result: state updated, event never published → inconsistent

WITH OUTBOX (safe):
  1. DB write + outbox WRITE     → single COMMIT
  2. Outbox poller reads + delivers → eventually consistent
     Result: at-least-once delivery; idempotency on consumer side prevents duplicates
```

### Outbox Table Structure

```sql
domain_events_outbox (
  id           UUID PRIMARY KEY,
  event_type   VARCHAR NOT NULL,
  aggregate_id VARCHAR NOT NULL,
  merchant_id  UUID NOT NULL,
  payload      JSONB NOT NULL,
  status       VARCHAR NOT NULL,  -- PENDING | PROCESSED | DLQ
  created_at   TIMESTAMPTZ NOT NULL,
  processed_at TIMESTAMPTZ,
  attempt_count INT DEFAULT 0
)
```

### Outbox → DLQ Flow

```
PENDING → [Poller picks up]
  → SUCCESS → PROCESSED
  → FAILURE (attempt_count < threshold) → back to PENDING with delay
  → FAILURE (attempt_count >= threshold) → DLQ
```

DLQ events are visible at `GET /ops/outbox/dlq` and can be replayed via `POST /ops/outbox/replay/{id}`.

---

## Why Some Things Are Sync and Others Async: Decision Criteria

| Criterion | Make it Sync | Make it Async |
|---|---|---|
| User is actively waiting for the result | ✓ | |
| Financial state must be committed atomically | ✓ | |
| Result is required by the same operation | ✓ | |
| Downstream system may be slow/unreliable | | ✓ |
| Batch / scheduled operation | | ✓ |
| Notification / derived state | | ✓ |
| Can tolerate eventual consistency | | ✓ |
| Must never be partial (partial = invalid) | ✓ | |

---

## Consistency Guarantees Summary

| Path | Consistency | Mechanism |
|---|---|---|
| `COMMIT (business state + outbox)` | ✓ Strong | Single ACID transaction |
| Outbox → Events publication | ~ Eventual (seconds) | Poller |
| Events → Projection update | ~ Eventual (seconds–minutes) | Async listener |
| Events → Webhook delivery | ~ Eventual (seconds–minutes) | Delivery worker + retry |
| Revenue recognition | ~ Eventual (hours, nightly) | Cron + REQUIRES_NEW |
| Reconciliation | ~ Eventual (hours, nightly) | Cron + JobLock |
| Ledger snapshots | ~ Eventual (hours, nightly) | Cron |
| Ops projections (Phase 11) | ~ Eventual (seconds–minutes) | Async listener |

---

## Phase 11 — Ops & Summary Projections: Async Consumers

### Event → Projection Mapping

`ProjectionEventListener.onDomainEventRecorded` now dispatches to four additional
projection update methods after the existing `customer_billing_summary` and
`merchant_daily_kpi` handlers:

```
DomainEventRecordedEvent
  └── applyEventToCustomerBillingProjection(de)      ← existing
  └── applyEventToMerchantDailyKpi(de)               ← existing
  └── applyEventToSubscriptionStatusProjection(de)   ← Phase 11
  └── applyEventToInvoiceSummaryProjection(de)       ← Phase 11
  └── applyEventToPaymentSummaryProjection(de)       ← Phase 11
  └── applyEventToReconDashboardProjection(de)       ← Phase 11
```

All six calls run inside `@Async` — a single failure logs a stale-projection
warning but **never rolls back the originating write transaction**.

### Eventual Consistency Guarantees

- A projection row may be up to a few seconds behind the source tables while the
  async thread pool is busy.
- Because each update re-reads from source tables (full-re-read strategy), late
  delivery of events does not cause permanent drift.
- Projections are always rebuildable via the admin rebuild API — they are
  **derived data**, not a system of record.

### Idempotency

All `applyEventTo*` methods are idempotent: applying the same event twice yields
the same projection state as applying it once, because the update is a full upsert
from the source tables rather than an incremental mutation.

Rebuild methods (`rebuildSubscriptionStatusProjection`, etc.) perform
`deleteAllInBatch()` followed by a full source-table scan — also idempotent.

---

## Phase 12: Unified Timeline — Async Consumer

### Overview

Timeline row creation follows the same async pattern as projection updates:
`ProjectionEventListener` calls `TimelineService.appendFromEvent(de)` on the async
executor thread **after** the originating transaction commits.

```
[HTTP Request] → [Service @Transactional] → [DomainEventService.save()]
                                          → [ApplicationEventPublisher.publish()]
                                                       ↓ (async)
                                          [ProjectionEventListener]  
                                            ├── projectionUpdateService.*
                                            ├── opsProjectionUpdateService.*
                                            └── timelineService.appendFromEvent(de)  ← Phase 12
```

### Eventual consistency

- Timeline rows are written **asynchronously** — they may lag the originating
  write by up to a few hundred milliseconds.
- This is acceptable: the timeline is a support/ops tool, not a transactional
  read path that needs strict consistency.
- If timeline rows are missing after a restart, the DB source tables remain intact;
  a future rebuild command can regenerate timeline rows from domain events.

### Dedup and replay safety

Timeline rows are deduplicated by `(source_event_id, entity_type, entity_id)`
using a **unique partial index** (`WHERE source_event_id IS NOT NULL`).

```
Timeline row A: source_event_id=99, entity_type=SUBSCRIPTION, entity_id=10  ✓ unique
Timeline row B: source_event_id=99, entity_type=CUSTOMER,      entity_id=20  ✓ unique (different entity)
On replay:
  existsDedup(99, SUBSCRIPTION, 10) = true  → skipped
  existsDedup(99, CUSTOMER, 20)     = true  → skipped
```

- Code-level check in `TimelineService.appendFromEvent` prevents unnecessary INSERTs.
- DB constraint catches the rare race window between two concurrent listeners.
- A `DataIntegrityViolationException` from a concurrent race is caught and logged
  as WARN (not ERROR) — the DB constraint has already guaranteed correctness.

### Failure isolation

A failure in `timelineService.appendFromEvent` is caught by the shared try/catch
in `ProjectionEventListener` and logged as an error, but **does not roll back** the
originating transaction or affect any other projection update.  Timeline rows can
always be reconstructed from the `domain_events` table.
