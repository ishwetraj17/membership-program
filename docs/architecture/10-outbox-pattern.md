# Outbox Pattern — Phase 12: Leasing, Starvation Prevention, and Per-Aggregate Ordering

## Overview

The transactional outbox guarantees at-least-once delivery of domain events by
writing them to the same database transaction as the business change.  Phase 12
hardens the poller to behave correctly under retries, long-running handlers, and
competing worker nodes.

---

## Key Concepts

### 1. The Lease

When a poller claims an event for processing it stamps two fields:

| Column | Purpose |
|---|---|
| `processing_owner` | Stable `hostname:pid` of the claiming JVM (see `OutboxLeaseHeartbeat.NODE_ID`) |
| `lease_expires_at` | `now + 5 min` — the deadline by which the handler must complete or the heartbeat must renew |

A live handler sends a **heartbeat** every 60 seconds (via `OutboxLeaseHeartbeat`)
that extends `lease_expires_at` by another 5 minutes.  If the JVM crashes or is
killed without cleanly releasing the event, the heartbeat stops and the lease
naturally expires.

### 2. Stale Lease Recovery

`OutboxLeaseRecoveryService` runs two complementary strategies (triggered by the
poller every 5 minutes, and on-demand via `POST /ops/outbox/recover-stale-leases`):

| Strategy | Condition | Target events |
|---|---|---|
| **Lease-based** | `lease_expires_at < now` | Phase 12+ events with a lease set |
| **Legacy time-based** | `processing_started_at < now - 5 min` AND `lease_expires_at IS NULL` | Events created before V58 (no lease column) |

Events matching either condition are reset to `NEW` with `next_attempt_at = now`
so the next poll cycle picks them up.

**Safety guarantee**: events belonging to a live node are protected because their
heartbeat keeps `lease_expires_at` in the future.  Only truly abandoned leases
(heartbeat stopped) expire and are recovered.

### 3. Retry Starvation Prevention

**The problem**: without prioritisation, events are polled globally by
`next_attempt_at ASC`.  A batch of permanently-failing old events (whose
`next_attempt_at` is perpetually in the past) will always sort before freshly
published events — silently blocking new work indefinitely.

**The solution** (`OutboxPrioritySelector`): the poll batch is filled in two tiers:

```
Tier 1: fresh events  (attempts = 0) — ordered by created_at ASC
         ↓ fill remaining slots
Tier 2: retry events  (attempts > 0) — ordered by next_attempt_at ASC
```

Fresh events always get the first slots in every poll batch, regardless of how
old the retries are.  Old retrying events can never starve new ones.

### 4. Per-Aggregate Ordering

Events that belong to the same aggregate (e.g. all events for subscription
`sub-42`) must be consumed in the order they were produced to maintain correct
state.

`OutboxAggregateSequencer` assigns a monotonically increasing `aggregate_sequence`
value per `(aggregate_type, aggregate_id)` pair when an event is published.  This
is computed as `MAX(aggregate_sequence) + 1` within the same write transaction.

Consumers can read events in order by sorting on `aggregate_sequence`:

```sql
SELECT * FROM outbox_events
WHERE aggregate_type = 'Subscription' AND aggregate_id = 'sub-42'
ORDER BY aggregate_sequence ASC;
```

> **Note**: Ordering is a metadata guarantee, not a delivery guarantee.  The
> poller does not currently enforce per-aggregate serial delivery.  Consumers
> that require strict ordering must implement their own sequencing check using
> `aggregate_sequence`.

### 5. Compile-Time-Safe Handler Registration

`OutboxEventType` enumerates all event types that **must** have a handler.
`OutboxEventHandlerRegistry` validates at Spring startup that every enum constant
has a corresponding `@Component` handler.  If any type is missing, the application
fails to start with a clear error listing the missing types.

This transforms a runtime silent-data-loss risk into an immediate deploy-time
failure.

---

## Schema (V58)

```sql
-- New columns added
ALTER TABLE outbox_events
    ADD COLUMN aggregate_sequence BIGINT    NULL,   -- per-aggregate ordering
    ADD COLUMN lease_expires_at   TIMESTAMP NULL;   -- heartbeat lease deadline

-- Indexes
idx_outbox_fresh_events    -- partial: status='NEW' AND attempts=0, ordered by created_at
idx_outbox_retry_events    -- partial: status='NEW' AND attempts>0, ordered by next_attempt_at
idx_outbox_aggregate_order -- (aggregate_type, aggregate_id, aggregate_sequence) WHERE not null
idx_outbox_lease_expiry    -- lease_expires_at WHERE status='PROCESSING'
```

---

## Class Inventory

| Class | Package | Role |
|---|---|---|
| `OutboxEventType` | `com.firstclub.outbox` | Enum of event types requiring a handler |
| `OutboxPrioritySelector` | `com.firstclub.outbox.ordering` | Two-tier batch selection (fresh first) |
| `OutboxAggregateSequencer` | `com.firstclub.outbox.ordering` | Assigns per-aggregate sequence numbers |
| `OutboxLeaseHeartbeat` | `com.firstclub.outbox.lease` | Scheduled heartbeat that renews leases |
| `OutboxLeaseRecoveryService` | `com.firstclub.outbox.lease` | Recovers expired/stale leases |
| `OutboxOpsController` | `com.firstclub.outbox.api` | Admin ops endpoints |
| `OutboxEventHandlerRegistry` | `com.firstclub.outbox.handler` | Registry + startup validation |
| `OutboxService` | `com.firstclub.outbox.service` | Core publish/poll/requeue orchestration |

---

## Ops API Reference

All endpoints require `ROLE_ADMIN`.

### GET `/ops/outbox/lag`

Returns current outbox health metrics:

```json
{
  "newCount": 12,
  "processingCount": 3,
  "failedCount": 0,
  "doneCount": 9812,
  "totalPending": 15,
  "byEventType": { "INVOICE_CREATED": 8, "PAYMENT_SUCCEEDED": 4 },
  "staleLeasesCount": 0,
  "oldestPendingAgeSeconds": 47,
  "reportedAt": "2026-03-09T04:00:00Z"
}
```

### POST `/ops/outbox/recover-stale-leases`

Triggers immediate stale-lease recovery without waiting for the scheduler.

```json
{
  "lease_based_recovered": 2,
  "legacy_recovered": 0,
  "total_recovered": 2
}
```

### POST `/ops/outbox/requeue/{id}`

Resets a single event (any status) to `NEW` with `next_attempt_at = now`.

```bash
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  https://api.example.com/ops/outbox/requeue/12345
```

```json
{ "id": 12345, "status": "REQUEUED" }
```

---

## Adding a New Event Type

1. Add a constant to `OutboxEventType`.
2. Create a `@Component` implementing `OutboxEventHandler` with `getEventType()` returning the constant's `name()`.
3. If your handler should be idempotent (strongly recommended), extend `DedupAwareOutboxHandler`.
4. Add a migration or seed the handler name to `DomainEventTypes` for documentation.

**If step 2 is skipped**, the application will fail to start with:
```
IllegalStateException: OutboxEventHandlerRegistry: missing handlers for
required event type(s): [YOUR_NEW_TYPE]. Every OutboxEventType constant must
have a corresponding @Component handler.
```

---

## Severity SLAs and Alert Thresholds

| Metric | Warning | Critical |
|---|---|---|
| `newCount` age > 5 min | ✓ | — |
| `newCount` age > 30 min | — | ✓ |
| `failedCount` > 0 | ✓ | — |
| `staleLeasesCount` > 0 after recovery | — | ✓ |
| `processingCount` stuck for > 10 min | ✓ | — |

---

## Background: Why Not Just Use a Message Broker?

The transactional outbox solves the **dual-write problem**: without it, a service
must atomically update the database AND publish a message to Kafka/RabbitMQ — two
separate systems that cannot participate in a single ACID transaction.  If the
service crashes between the DB commit and the broker publish, the event is silently
lost.

By writing the event to the same DB transaction as the business change, we
guarantee the event is stored **iff** the business change is committed.  A separate
background poller then delivers it to downstream consumers, with at-least-once
semantics and structured retry/DLQ handling.
