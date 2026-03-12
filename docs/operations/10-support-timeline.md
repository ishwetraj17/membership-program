# Support Cases & Entity Timeline

> **Phase 20** — Ops debuggability with append-only timelines and structured
> support cases.

---

## Overview

The platform provides two complementary tools for operational visibility:

| Tool | Purpose | Where |
|------|---------|-------|
| **Entity timeline** | Append-only log of everything that happened to an entity | `GET /ops/timeline` |
| **Support cases** | Structured case tracking linked to any entity | `POST /support/cases` |

Together they answer: *"What happened, in what order, and what did we do about it?"*

---

## 1. The Entity Timeline

### What is it?

The timeline is an **append-only, immutable log** of events for every platform
entity. Events come from two sources:

1. **Domain events** — automatically projected by `TimelineMapper` when an event
   passes through the outbox (e.g. `SUBSCRIPTION_CREATED`, `PAYMENT_SUCCEEDED`).
2. **Manual repair actions** — written by `TimelineEventFactory` when an operator
   triggers a repair via the Ops Command Center.

Timeline entries are **never deleted or modified**. They represent the ground
truth of what the system recorded, plus what operators did to the system.

### Entity types

| `entityType` | Represents |
|---|---|
| `CUSTOMER` | A customer profile |
| `SUBSCRIPTION` | A billing subscription |
| `INVOICE` | A single invoice |
| `PAYMENT_INTENT` | A payment attempt |
| `REFUND` | An issued refund |
| `DISPUTE` | A chargeback dispute |
| `RECON_MISMATCH` | A reconciliation discrepancy |
| `OUTBOX_EVENT` | A failed/retried outbox message |
| `WEBHOOK_DELIVERY` | A webhook delivery attempt |
| `DLQ_MESSAGE` | A dead-letter queue entry |
| `LEDGER_SNAPSHOT` | A ledger balance snapshot rebuild |
| `RECON_REPORT` | A recon report (re-)run |

### Reading the timeline

```
GET /ops/timeline
    ?merchantId=<merchantId>
    &entityType=<entityType>
    &entityId=<entityId>
```

Returns all events for the entity in ascending `event_time` order (oldest first).

**Example — full subscription lifecycle:**
```json
[
  { "eventType": "subscription_created",   "title": "Subscription #42 created",      "eventTime": "2025-06-01T10:00:00" },
  { "eventType": "invoice_finalized",      "title": "Invoice #100 finalized",        "eventTime": "2025-06-01T10:01:00" },
  { "eventType": "payment_attempt_started","title": "Payment started",               "eventTime": "2025-06-01T10:01:05" },
  { "eventType": "payment_failed",         "title": "Payment #77 failed",            "eventTime": "2025-06-01T10:01:08" },
  { "eventType": "repair.invoice_rebuild", "title": "Invoice #100 totals rebuilt",   "eventTime": "2025-06-02T09:15:00" },
  { "eventType": "payment_succeeded",      "title": "Payment #78 succeeded",         "eventTime": "2025-06-02T09:16:00" }
]
```

### Manual repair event types

| `eventType` | Triggered by |
|---|---|
| `repair.outbox_retry` | `POST /ops/outbox/retry/{id}` |
| `repair.webhook_retry` | `POST /ops/webhooks/retry/{id}` |
| `repair.recon_rerun` | `POST /ops/recon/rerun` |
| `repair.invoice_rebuild` | `POST /ops/invoices/{id}/rebuild` |
| `repair.ledger_rebuild` | `POST /ops/ledger/snapshots/rebuild` |
| `repair.dlq_requeue` | `POST /ops/dlq/{id}/requeue` |
| `repair.mismatch_ack` | `POST /ops/mismatches/{id}/acknowledge` |

All repair events include `[actor=user:X]` in their `summary` field when
`actorUserId` is provided, making them attributable to specific operators.

---

## 2. Support Cases

### Purpose

A support case is a structured work item that:
- Links to one or more platform entities (subscription, invoice, dispute, etc.)
- Has a status lifecycle: **OPEN → IN_PROGRESS → PENDING_CUSTOMER → RESOLVED → CLOSED**
- Accumulates immutable notes from operators

Cases are for **tracking resolution** of known issues, as opposed to the timeline
which is an automatic passive observation log.

### Case lifecycle

```
OPEN
 │  operator picks up the case
 ▼
IN_PROGRESS
 │  waiting for merchant/customer response
 ▼
PENDING_CUSTOMER
 │  issue is fixed internally
 ▼
RESOLVED
 │  merchant confirms resolution
 ▼
CLOSED  (terminal — no further notes can be added)
```

### Creating a case

```
POST /support/cases
Content-Type: application/json

{
  "merchantId": 1,
  "linkedEntityType": "INVOICE",
  "linkedEntityId": 100,
  "title": "Invoice 100 shows incorrect total after plan change",
  "priority": "HIGH"
}
```

**Supported `linkedEntityType` values:**
`CUSTOMER`, `SUBSCRIPTION`, `INVOICE`, `PAYMENT_INTENT`, `REFUND`, `DISPUTE`, `RECON_MISMATCH`

### Adding notes

Notes are **immutable** after creation. Use them to capture investigation
progress, decisions made, and messages to the merchant.

```
POST /support/cases/{id}/notes
Content-Type: application/json

{
  "noteText": "Invoice totals rebuilt via /ops/invoices/100/rebuild. Grand total now matches line items.",
  "authorUserId": 9,
  "visibility": "MERCHANT_VISIBLE"
}
```

**Note visibility:**

| `visibility` | Visible to | Use for |
|---|---|---|
| `INTERNAL_ONLY` (default) | Platform operators only | Internal investigation notes, workaround details |
| `MERCHANT_VISIBLE` | Merchant via portal | Status updates, resolutions, action requests |

### Reading notes

```
GET /support/cases/{id}/notes
    ?visibility=MERCHANT_VISIBLE    (optional filter)
```

Omit `visibility` to retrieve all notes. Pass `visibility=MERCHANT_VISIBLE` to
get only notes that should appear in the merchant portal.

### Case + timeline integration

When working a support case, use the timeline to understand what happened:

1. Open the case linked to the affected entity.
2. Read the entity's timeline: `GET /ops/timeline?merchantId=X&entityType=INVOICE&entityId=Y`
3. Identify the failing event (look for `payment_failed`, unexpected status
   transitions, or missing events).
4. Run the appropriate repair: `/ops/invoices/{id}/rebuild`, `/ops/recon/rerun`, etc.
5. Verify the repair produced the expected follow-up timeline events.
6. Add a `MERCHANT_VISIBLE` note to the case describing the resolution.
7. Close the case.

---

## 3. Ops Command Center quick reference

All endpoints require `ADMIN` role.

| Method | Path | Purpose |
|--------|------|---------|
| `GET`  | `/ops/timeline` | Read entity timeline |
| `POST` | `/ops/outbox/retry/{id}` | Reset outbox event to NEW |
| `POST` | `/ops/webhooks/retry/{id}` | Reset webhook delivery to PENDING |
| `POST` | `/ops/recon/rerun` | Rerun reconciliation for a date |
| `POST` | `/ops/invoices/{id}/rebuild` | Recompute invoice totals |
| `POST` | `/ops/ledger/snapshots/rebuild` | Rebuild ledger balance snapshot |
| `POST` | `/ops/dlq/{id}/requeue` | Move DLQ entry back to outbox |
| `POST` | `/ops/mismatches/{id}/acknowledge` | Mark recon mismatch as acknowledged |

All `POST` endpoints accept `actorUserId` and `reason` query parameters for audit
attribution.  All return a `RepairActionResult` JSON body with `success`,
`repairKey`, and `details` fields.
