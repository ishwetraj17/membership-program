# DLQ and Retry Runbook

> **Phase 16 revision** — Updated to reflect the actual `dead_letter_messages`
> table, `/api/v2/admin/system/dlq` API paths, failure categorisation, and
> stale-lease recovery introduced in Phase 16.

This runbook covers how to inspect, diagnose, and drain the Dead Letter Queue (DLQ) for outbox events and webhook deliveries.

---

## What Is the DLQ?

Events in the `outbox_events` table that fail processing beyond **5 attempts** are
written to `dead_letter_messages` and their row moves to `status = FAILED`.
This prevents the poller from endlessly retrying an event that will never succeed.

The payload is stored in `dead_letter_messages.payload` as:
```
{eventType}|{jsonPayload}
```
For example: `INVOICE_CREATED|{"invoiceId":42,"merchantId":1}`.

DLQ events are **not lost data** — the originating business transaction already
committed.  The DLQ only affects downstream propagation (projections, webhooks,
notifications).  Financial state is correct even if the DLQ is non-empty.

---

## Failure Categories

Phase 16 adds a `failure_category` column to both `outbox_events` and
`dead_letter_messages`.  The category is set automatically when an event fails
and is the fastest way to determine the correct remediation.

| Category | Meaning | Action |
|---|---|---|
| `TRANSIENT_ERROR` | Network blip, DB timeout | Safe to retry immediately |
| `PAYLOAD_PARSE_ERROR` | Bad JSON in payload | Fix publisher and replay |
| `DEDUP_DUPLICATE` | Handler saw this as already processed | Investigate; safe to discard |
| `ACCOUNTING_ERROR` | Ledger/journal write failed | Fix accounting state first |
| `BUSINESS_RULE_VIOLATION` | Balance or refund limit exceeded | Resolve business state first |
| `HANDLER_NOT_FOUND` | No handler registered for this event type | Deploy handler; replay |
| `UNKNOWN` | Catch-all for unclassified errors | Investigate last_error |

---

## Inspecting the DLQ

### All DLQ entries

```
GET /api/v2/admin/system/dlq
Authorization: Bearer <admin-token>
```

### Filter by source

```
GET /api/v2/admin/system/dlq?source=OUTBOX
```

### Filter by failure category

```
GET /api/v2/admin/system/dlq?failureCategory=TRANSIENT_ERROR
```

### Filter by both

```
GET /api/v2/admin/system/dlq?source=OUTBOX&failureCategory=TRANSIENT_ERROR
```

### Aggregate summary (counts grouped by source and category)

```
GET /api/v2/admin/system/dlq/summary
```

Sample response:
```json
{
  "totalCount": 12,
  "bySource": { "OUTBOX": 10, "WEBHOOK": 2 },
  "byFailureCategory": {
    "TRANSIENT_ERROR": 8,
    "PAYLOAD_PARSE_ERROR": 2,
    "UNKNOWN": 2
  },
  "reportedAt": "2025-07-01T10:30:00"
}
```

### Individual entry response shape

```json
{
  "id": 7,
  "source": "OUTBOX",
  "payload": "INVOICE_CREATED|{\"invoiceId\":42}",
  "error": "connection timeout after 5000ms",
  "createdAt": "2025-07-01T09:00:00",
  "outboxEventType": "INVOICE_CREATED",
  "failureCategory": "TRANSIENT_ERROR",
  "merchantId": 3
}
```

### Via Deep Health

```
GET /api/v2/admin/system/health/deep
```

The `dlqDepth` field shows the total DLQ count.

### Via Database

```sql
-- All DLQ entries (newest first)
SELECT id, source, failure_category, merchant_id, error, created_at
FROM dead_letter_messages
ORDER BY created_at DESC
LIMIT 50;

-- DLQ entries by failure category
SELECT failure_category, COUNT(*) AS cnt
FROM dead_letter_messages
GROUP BY failure_category ORDER BY cnt DESC;

-- Outbox events currently processing (check for stale leases)
SELECT id, event_type, processing_owner, processing_started_at,
       NOW() - processing_started_at AS age
FROM outbox_events
WHERE status = 'PROCESSING'
ORDER BY processing_started_at;
```

---

## Outbox Lag (Pending Events)

```
GET /api/v2/admin/system/outbox
```
or the alias:
```
GET /api/v2/admin/system/outbox/lag
```

Sample response:
```json
{
  "newCount": 4,
  "processingCount": 1,
  "failedCount": 2,
  "doneCount": 9512,
  "totalPending": 7,
  "byEventType": { "INVOICE_CREATED": 3, "PAYMENT_SUCCEEDED": 4 },
  "staleLeasesCount": 0,
  "oldestPendingAgeSeconds": 42,
  "reportedAt": "2025-07-01T10:30:00"
}
```

**Key fields:**
- `staleLeasesCount` — events stuck in PROCESSING for > 5 minutes (indicates a JVM crash mid-processing; recovered automatically by the stale-lease scheduler).
- `oldestPendingAgeSeconds` — age in seconds of the oldest NEW or PROCESSING event.  Values > 300 (5 min) indicate the poller is falling behind.

---

## Stale Lease Recovery

When a JVM instance crashes while processing an outbox event, the row stays in
`status = PROCESSING` indefinitely.  Phase 16 adds automatic stale-lease recovery:

- Every **5 minutes**, `OutboxPoller.recoverStaleLeases()` runs.
- Events with `processing_started_at < NOW() - 5 minutes` are reset to `status = NEW`.
- The `processing_owner` and `processing_started_at` columns are cleared.

**Manual trigger (via DB — only when automated recovery is not running):**
```sql
UPDATE outbox_events
SET status = 'NEW',
    processing_owner = NULL,
    processing_started_at = NULL,
    next_attempt_at = NOW()
WHERE status = 'PROCESSING'
  AND processing_started_at < NOW() - INTERVAL '10 minutes';
```

---

## Retrying a Single DLQ Entry

```
POST /api/v2/admin/system/dlq/{id}/retry
Authorization: Bearer <admin-token>
```

This:
1. Parses `{eventType}|{jsonPayload}` from the DLQ payload.
2. Creates a new `outbox_events` row with `status = NEW`, `attempts = 0`, and the correct `eventType`.
3. Deletes the DLQ record.
4. Returns the original DLQ entry as confirmation.

**Before retrying, confirm:**
- The root cause is resolved (e.g. a `TRANSIENT_ERROR` has cleared).
- For `DEDUP_DUPLICATE`, decide whether to retry or discard — retrying a true duplicate may silently no-op if the handler's dedup guard fires, or cause a double-effect if not.

---

## Root Cause Categories and Remediation

### TRANSIENT_ERROR — safe to replay immediately

- DB connection blip during outbox poll.
- Brief network error to downstream service.
- Downstream temporarily unavailable.

```
GET /api/v2/admin/system/dlq?failureCategory=TRANSIENT_ERROR
POST /api/v2/admin/system/dlq/{id}/retry  ← per-entry
```

### PAYLOAD_PARSE_ERROR — fix publisher first

- Schema change broke event serialization.
- Missing/null required field in JSON.

1. Fix the publisher code.
2. Deploy.
3. Replay the DLQ entries that match — the corrected event will be published fresh; the DLQ entries represent the old malformed version.

### DEDUP_DUPLICATE — investigate before replay

- Handler correctly identified an already-processed event.
- Safe to delete the DLQ entry if the effect was already applied.

### ACCOUNTING_ERROR — fix accounting state first

- Ledger account missing or balance rule violated.
- Fix the accounting configuration, then replay.

### BUSINESS_RULE_VIOLATION — resolve business state first

- Over-refund attempt; balance insufficient.
- Resolve with the merchant/ops team, then replay or discard.

### HANDLER_NOT_FOUND — deploy handler

- Event type has no registered handler.
- Deploy the handler registration, then replay.

---

## Outbox Poller Health

The outbox poller runs every **30 seconds** (batch of 50 events).
Stale-lease recovery runs every **5 minutes**.

**Signs the poller is falling behind:**

| Signal | API to check | Threshold |
|---|---|---|
| `oldestPendingAgeSeconds` rising | `GET /outbox/lag` | > 300 s = Warning |
| `staleLeasesCount` > 0 | `GET /outbox/lag` | > 0 = Investigate |
| `totalPending` growing | `GET /outbox/lag` | > 1000 = Warning |
| `dlqDepth` > 10 | `GET /health/deep` | > 100 = Critical |

**Mitigation:**
- Rule out DB issue first: `GET /api/v2/admin/system/health/deep`.
- Check Redis availability (idempotency guards may be failing open): `GET /api/v2/admin/system/redis/health`.
- If processing is merely slow: this is normal under load; monitor until it clears.
- If `staleLeasesCount` > 0 and not recovering automatically: check that the `recoverStaleLeases` scheduled task is running (look for `OutboxPoller: stale lease recovery` log lines).

---

## SLA and Monitoring Thresholds

| Metric | Warning | Critical |
|---|---|---|
| DLQ depth | > 10 | > 100 |
| `oldestPendingAgeSeconds` | > 300 s (5 min) | > 1800 s (30 min) |
| `totalPending` | > 1000 | > 10 000 |
| `staleLeasesCount` | > 0 | > 5 |
| Webhook endpoint failure rate | > 20% | > 50% |

---

## Step-by-Step: DLQ Incident Response

```
1. Alert fires: DLQ depth > 100 or oldestPendingAgeSeconds > 1800

2. Get DLQ summary
   GET /api/v2/admin/system/dlq/summary
   → Identify dominant failureCategory and source

3. Get outbox lag
   GET /api/v2/admin/system/outbox/lag
   → Check staleLeasesCount, totalPending, oldestPendingAgeSeconds

4. Check deep health
   GET /api/v2/admin/system/health/deep
   → Is DB up? Is Redis up?

5. Identify root cause using failureCategory (see table above)

6. Fix root cause if required
   → Do NOT replay before fixing — it will re-DLQ immediately

7. Verify fix
   → Watch a freshly published test event process successfully through the outbox

8. Replay DLQ entries
   → Filter by failureCategory/source first
   → GET /api/v2/admin/system/dlq?source=OUTBOX&failureCategory=TRANSIENT_ERROR
   → POST /api/v2/admin/system/dlq/{id}/retry for each entry
      (bulk replay endpoint planned for a future phase)

9. Monitor
   → Poll GET /api/v2/admin/system/outbox/lag every 2 minutes
   → Watch totalPending decrease and dlqDepth return to 0

10. Document
    → Date/time, root cause, failureCategory mix, entries retried/discarded
    → Add to incident log
```

---

## Webhook Delivery DLQ

Webhook deliveries have their own failure path.  A `WebhookDelivery` row moves to
`status = PERMANENTLY_FAILED` after the endpoint is disabled.  This is separate
from the outbox DLQ.

### Inspect failed webhook deliveries

```
GET /api/v2/webhooks/deliveries?merchantId=X&status=PERMANENTLY_FAILED
```

### Re-enable a disabled endpoint

```
PATCH /api/v2/webhooks/endpoints/{endpointId}
{ "active": true }
```

### Replay a failed delivery

```
POST /api/v2/webhooks/deliveries/{deliveryId}/replay
```


---

## What Is the DLQ?

Events in the `domain_events_outbox` table that fail processing beyond the retry threshold move to `status = DLQ`. This prevents the poller from repeatedly attempting an event that will never succeed.

DLQ events are **not lost data** — the underlying business operation already committed. The DLQ only affects downstream systems (projections, webhooks, notifications). Financial state is correct even if the DLQ is full.

---

## Inspecting the DLQ

### Via API

```
GET /ops/outbox/dlq
```

Response:
```json
{
  "totalInDlq": 12,
  "events": [
    {
      "id": "uuid-...",
      "eventType": "PAYMENT_SUCCEEDED",
      "aggregateId": "payment-uuid-...",
      "merchantId": "merchant-uuid-...",
      "attemptCount": 5,
      "lastAttemptAt": "2025-01-15T03:12:00Z",
      "lastError": "Connection refused: webhook endpoint unreachable",
      "status": "DLQ",
      "createdAt": "2025-01-15T02:30:00Z"
    }
  ]
}
```

### Via Deep Health

```
GET /ops/health/deep
```

The `outboxLag` field shows the count of PENDING events older than the lag threshold. The `dlqDepth` field shows the DLQ count.

### Via Database

```sql
-- DLQ events
SELECT id, event_type, aggregate_id, merchant_id, attempt_count, last_error, created_at
FROM domain_events_outbox
WHERE status = 'DLQ'
ORDER BY created_at DESC
LIMIT 50;

-- PENDING events older than 5 minutes (outbox lag indicator)
SELECT count(*) FROM domain_events_outbox
WHERE status = 'PENDING'
  AND created_at < NOW() - INTERVAL '5 minutes';
```

---

## Root Cause Categories

### Category 1: Transient Failure (safe to replay)

- DB connection blip during outbox poll
- Brief network error to webhook endpoint
- Downstream service temporarily unavailable

**Action:** Replay events immediately.

### Category 2: Deserialization Error (fix first)

- Schema change in event payload that broke deserialization
- Missing field that a consumer expects

**Action:** Fix the deserialization/schema issue → deploy → then replay.

### Category 3: Consumer Bug (fix first)

- A specific event type throws an exception in the handler
- Example: `REVENUE_RECOGNIZED` handler throws NPE

**Action:** Fix the handler bug → deploy → replay affected events.

### Category 4: Webhook Endpoint Permanently Down

- Merchant's registered webhook endpoint is returning 500 consistently
- The webhook endpoint was disabled after threshold failures

**Action:** Contact merchant to restore endpoint → re-enable endpoint → replay deliveries (not outbox events).

---

## Replaying Outbox Events

### Replay a Single Event

```
POST /ops/outbox/replay/{eventId}
```

This resets `status = PENDING`, `attempt_count = 0` for the specified event. The outbox poller will pick it up on the next poll cycle.

### Replay All DLQ Events of a Specific Type

```
POST /ops/outbox/replay/bulk
{
  "eventType": "PAYMENT_SUCCEEDED",
  "since": "2025-01-15T00:00:00Z"
}
```

### Replay All DLQ Events for a Merchant

```
POST /ops/outbox/replay/bulk
{
  "merchantId": "merchant-uuid-...",
  "since": "2025-01-14T00:00:00Z"
}
```

**Safety check before bulk replay:**
1. Confirm the root cause is fixed
2. Ensure dedup guards are in place (business fingerprints, idempotency keys)
3. Replay does not duplicate money effects — each event handler checks current state before acting

---

## Webhook Delivery DLQ

Webhook deliveries have their own failure path. A `WebhookDelivery` row moves to `status = PERMANENTLY_FAILED` after the endpoint is disabled.

### Inspect Failed Webhook Deliveries

```
GET /api/v2/webhooks/deliveries?merchantId=X&status=PERMANENTLY_FAILED
```

### Re-enable a Disabled Webhook Endpoint

```
PATCH /api/v2/webhooks/endpoints/{endpointId}
{
  "active": true
}
```

### Replay Failed Deliveries

```
POST /api/v2/webhooks/deliveries/{deliveryId}/replay
```

---

## Outbox Poller Health

The outbox poller is an `@Scheduled` job running every few seconds. It stops being effective if:

1. Its thread pool is saturated
2. The DB connection pool is exhausted
3. Each poll takes longer than the poll interval

**Check:**
```
GET /ops/health/deep
```
Look at `outboxLag.pendingCount` and `outboxLag.oldestPendingAge`. If `oldestPendingAge > 5 minutes` and trending upward, the poller is falling behind.

**Mitigation:**
- Reduce poll batch size (config: `app.outbox.batch-size`)
- Increase poll thread pool size
- Scale horizontally (multiple JVM instances each poll with `FOR UPDATE SKIP LOCKED`)

---

## SLA and Monitoring Thresholds

| Metric | Warning | Critical |
|---|---|---|
| DLQ depth | > 10 events | > 100 events |
| Outbox lag (oldest PENDING) | > 5 min | > 30 min |
| PENDING events count | > 1000 | > 10000 |
| Webhook endpoint failure rate | > 20% | > 50% |

These thresholds should feed into alerting once an observability stack is configured.

---

## Step-by-Step: DLQ Incident Response

```
1. Alert fires: DLQ depth > 100

2. Inspect DLQ events
   GET /ops/outbox/dlq
   → Note event types affected, merchant IDs, last errors

3. Check deep health
   GET /ops/health/deep
   → Is DB up? Is poller running?

4. Identify root cause category
   → Transient? Deserialization? Consumer bug? Webhook down?

5. Fix root cause (if needed)
   → Deploy fix
   → Do NOT replay before fix — you will just re-DLQ the events

6. Verify fix
   → Create a test event through the API
   → Watch it process successfully through the outbox

7. Replay DLQ events
   POST /ops/outbox/replay/bulk (or per-event)

8. Monitor
   GET /ops/health/deep every 2 minutes
   → Watch DLQ count decrease
   → Watch outbox lag return to normal

9. Document
   → Date/time, root cause, affected event types, replay action taken
   → Add to incident log
```

---

## Phase 20 — Ops Command Center requeue endpoint

Phase 20 adds a dedicated ops endpoint at `/ops/dlq/{id}/requeue` that wraps the
DLQ retry with **automatic timeline recording**:

```
POST /ops/dlq/{id}/requeue
    ?merchantId=<merchantId>
    &actorUserId=<yourUserId>
    &reason=<free text explanation>
```

**What it does over the existing retry:**
1. Re-enqueues the DLQ entry exactly as before.
2. Writes a `repair.dlq_requeue` timeline event on the entity timeline so ops
   can see who requeued it and when (searchable via `GET /ops/timeline`).

**Expected HTTP 200 response:**
```json
{
  "repairKey": "repair.dlq.requeue",
  "success": true,
  "dryRun": false,
  "details": "DLQ message 42 re-enqueued (outbox eventType=SUBSCRIPTION_CREATED)"
}
```

Use the legacy `/api/v2/admin/system/dlq/{id}/retry` endpoint for scripted bulk
operations where timeline annotation is not required, and the new
`/ops/dlq/{id}/requeue` for interactive manual repairs where an audit trail is
important.
