# Events and Replay — Developer Guide

## Overview

This document describes the domain event log system, event versioning (V29+),
and the replay/validation tooling for the FirstClub Membership Platform.

---

## Domain Event Log

All significant state changes are recorded as **immutable events** in the
`domain_events` table via `DomainEventLog`. Events are never updated or
deleted — they form a permanent audit trail.

### Recording an Event

**Simple (no metadata):**
```java
@Autowired DomainEventLog eventLog;

// With a pre-serialised JSON string
eventLog.record(DomainEventTypes.INVOICE_CREATED, "{\"invoiceId\":42}");

// With a payload map (serialised automatically)
eventLog.record(DomainEventTypes.PAYMENT_SUCCEEDED, Map.of(
    "invoiceId", 42,
    "subscriptionId", 7
));
```

**With metadata (V29+):**
```java
EventMetadataDTO meta = EventMetadataDTO.builder()
    .correlationId("req-abc-123")   // auto-populated from MDC.requestId if omitted
    .causationId("prev-event-id")
    .aggregateType("Subscription")
    .aggregateId("sub-42")
    .merchantId(9L)
    .eventVersion(1)               // default: 1
    .schemaVersion(1)              // default: 1
    .build();

eventLog.record(DomainEventTypes.SUBSCRIPTION_ACTIVATED, payload, meta);
```

### MDC Correlation ID

`DomainEventLog` automatically reads `MDC.get("requestId")` and uses it as the
`correlationId` when none is supplied via `EventMetadataDTO`. This links every
event to the inbound HTTP request that caused it (set by the request filter).

---

## Event Types

All event type constants live in `DomainEventTypes` (two copies kept in sync):

| Package | Class |
|---|---|
| `com.firstclub.events.service` | Used by `DomainEventLog`, `ReplayService` |
| `com.firstclub.outbox.config` | Used by `OutboxEvent` processing |

### Available Types (V29)

| Constant | Value |
|---|---|
| `SUBSCRIPTION_ACTIVATED` | `SUBSCRIPTION_ACTIVATED` |
| `SUBSCRIPTION_CREATED` | `SUBSCRIPTION_CREATED` |
| `SUBSCRIPTION_CANCELLED` | `SUBSCRIPTION_CANCELLED` |
| `SUBSCRIPTION_PAST_DUE` | `SUBSCRIPTION_PAST_DUE` |
| `SUBSCRIPTION_SUSPENDED` | `SUBSCRIPTION_SUSPENDED` |
| `INVOICE_CREATED` | `INVOICE_CREATED` |
| `PAYMENT_SUCCEEDED` | `PAYMENT_SUCCEEDED` |
| `PAYMENT_FAILED` | `PAYMENT_FAILED` |
| `PAYMENT_INTENT_CREATED` | `PAYMENT_INTENT_CREATED` |
| `PAYMENT_ATTEMPT_STARTED` | `PAYMENT_ATTEMPT_STARTED` |
| `PAYMENT_ATTEMPT_FAILED` | `PAYMENT_ATTEMPT_FAILED` |
| `REFUND_COMPLETED` | `REFUND_COMPLETED` |
| `DISPUTE_OPENED` | `DISPUTE_OPENED` |
| `SETTLEMENT_COMPLETED` | `SETTLEMENT_COMPLETED` |
| `RECON_COMPLETED` | `RECON_COMPLETED` |
| `RISK_DECISION_MADE` | `RISK_DECISION_MADE` |

---

## V29 Metadata Fields

Both `domain_events` and `outbox_events` tables gained 7 new columns in
migration `V29__event_versioning_and_metadata.sql`:

| Column | Type | Default | Purpose |
|---|---|---|---|
| `event_version` | `INT` | 1 | Monotonic event schema version |
| `schema_version` | `INT` | 1 | JSON payload schema version |
| `correlation_id` | `VARCHAR(255)` | null | Distributed trace / request ID |
| `causation_id` | `VARCHAR(255)` | null | ID of the causing event |
| `aggregate_type` | `VARCHAR(100)` | null | DDD aggregate label (e.g., `Subscription`) |
| `aggregate_id` | `VARCHAR(255)` | null | Aggregate PK as string |
| `merchant_id` | `BIGINT` | null | Owning merchant (tenant scoping) |

New indexes:

| Index | Columns | Purpose |
|---|---|---|
| `idx_domain_events_merchant_created` | `(merchant_id, created_at)` | Tenant-scoped replay |
| `idx_domain_events_aggregate` | `(aggregate_type, aggregate_id)` | Aggregate-level replay |
| `idx_outbox_events_merchant_status` | `(merchant_id, status)` | Outbox fan-out per tenant |

---

## Querying Events via REST (V2)

**Requires ADMIN role.**

### List events with optional filters

```
GET /api/v2/admin/events
```

| Query param | Type | Description |
|---|---|---|
| `merchantId` | `Long` | Filter by owning merchant |
| `eventType` | `String` | Filter by exact event type string |
| `aggregateType` | `String` | Filter by aggregate type label |
| `aggregateId` | `String` | Filter by aggregate ID string |
| `from` | `ISO-8601 datetime` | Window start |
| `to` | `ISO-8601 datetime` | Window end |
| `page` | `int` | Page number (0-based, default 0) |
| `size` | `int` | Page size (default 50) |
| `sort` | `string` | Sort field (default `createdAt,DESC`) |

**Example:**
```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v2/admin/events?merchantId=9&eventType=INVOICE_CREATED&size=20"
```

**Response:** `Page<EventListResponseDTO>`
```json
{
  "content": [
    {
      "id": 1,
      "eventType": "INVOICE_CREATED",
      "payload": "{\"invoiceId\":42}",
      "eventVersion": 1,
      "schemaVersion": 1,
      "correlationId": "req-abc-123",
      "causationId": null,
      "aggregateType": "Invoice",
      "aggregateId": "42",
      "merchantId": 9,
      "createdAt": "2024-06-01T10:00:00"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "size": 50,
  "number": 0
}
```

---

## Replay and Validation

### V1 — Legacy Replay (backwards-compatible)

```
POST /api/v1/admin/replay?from=2024-01-01T00:00:00&to=2024-01-31T23:59:59&mode=VALIDATE_ONLY
```

- Only `VALIDATE_ONLY` mode is supported.
- Returns `ReplayReportDTO`.

### V2 — Validate

```
POST /api/v2/admin/replay/validate
Content-Type: application/json
Authorization: Bearer $TOKEN

{
  "from": "2024-01-01T00:00:00",
  "to": "2024-01-31T23:59:59",
  "merchantId": 9,
  "aggregateType": "Subscription",
  "aggregateId": "sub-42"
}
```

Forces `mode = VALIDATE_ONLY`. Returns `ReplayResponseDTO` with per-type count
breakdown.

### V2 — Rebuild Projection

```
POST /api/v2/admin/replay/rebuild-projection
Content-Type: application/json

{
  "from": "2024-01-01T00:00:00",
  "to": "2024-01-31T23:59:59",
  "projectionName": "subscription_summary"
}
```

Forces `mode = REBUILD_PROJECTION`. Supported `projectionName` values:

| Name | Description |
|---|---|
| `subscription_summary` | Per-merchant subscription state snapshot |
| `invoice_ledger` | Invoice totals and payment status |

Returns 400 if an unsupported `projectionName` is provided.

### Invariants Validated

| Invariant | Finding key |
|---|---|
| No duplicate `INVOICE_CREATED` for same `invoiceId` | `DUPLICATE_INVOICE_CREATED` |
| Each `PAYMENT_SUCCEEDED` has a matching `INVOICE_CREATED` | `ORPHAN_PAYMENT_SUCCEEDED` |
| Each `SUBSCRIPTION_ACTIVATED` has a matching `PAYMENT_SUCCEEDED` | `ORPHAN_SUBSCRIPTION_ACTIVATED` |
| Ledger is globally balanced (total debits == total credits) | `LEDGER_UNBALANCED` |

---

## Scoping Rules for Replay

`ReplayService.fetchEvents(ReplayRequestDTO)` selects the most specific repo
method automatically:

| Scope | Repository method used |
|---|---|
| `aggregateType` + `aggregateId` both set | `findByAggregateTypeAndAggregateIdAndCreatedAtBetween...` |
| `aggregateType` set only | `findByAggregateTypeAndCreatedAtBetween...` |
| `merchantId` set only | `findByMerchantIdAndCreatedAtBetween...` |
| No scope filters | `findByCreatedAtBetween...` |

---

## Testing

| Test class | Type | Coverage |
|---|---|---|
| `DomainEventLogTest` | Unit (Mockito) | `record()` overloads, MDC pickup, map serialisation |
| `ReplayServiceTest` | Unit (Mockito) | Scoped fetch dispatch, all invariants, projection rebuild |
| `EventAdminControllerTest` | Integration (Testcontainers) | REST list with/without filters, auth |
| `ReplayControllerTest` | Integration (Testcontainers) | V1 legacy, V2 validate, V2 rebuild |
