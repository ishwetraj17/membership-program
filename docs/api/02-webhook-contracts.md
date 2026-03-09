# Webhook Contracts

This document describes how the FirstClub platform delivers outbound webhook events to merchant endpoints, including event types, payload schema, signature verification, retry behavior, and endpoint management.

---

## Event Delivery Model

Events are delivered asynchronously. When a business operation completes:
1. An outbox event is written in the same DB transaction as the business state change
2. The outbox poller picks it up and dispatches it to all registered webhook endpoints for the merchant
3. Each delivery attempt is recorded in `webhook_delivery_attempts`
4. Failures are retried with exponential backoff

**At-least-once delivery:** The same event may be delivered more than once (on retry). Merchants must handle duplicates. Each event carries a unique `eventId` that merchants can use for deduplication.

---

## Event Types

| Event Type | Trigger |
|---|---|
| `SUBSCRIPTION_CREATED` | New subscription record created |
| `SUBSCRIPTION_ACTIVATED` | Subscription first billing cycle begins |
| `SUBSCRIPTION_RENEWED` | Subscription successfully renewed |
| `SUBSCRIPTION_PAST_DUE` | Invoice unpaid after due date |
| `SUBSCRIPTION_CANCELLED` | Subscription cancelled (by merchant, customer, or system) |
| `SUBSCRIPTION_EXPIRED` | Fixed-term subscription has reached its end date |
| `INVOICE_CREATED` | Invoice generated for a billing cycle |
| `INVOICE_FINALIZED` | Invoice amount locked for collection |
| `INVOICE_PAID` | Payment successfully captured against invoice |
| `INVOICE_VOID` | Invoice voided without collection |
| `PAYMENT_INTENT_CREATED` | Payment intent created (awaiting confirmation) |
| `PAYMENT_CAPTURED` | Payment amount successfully captured |
| `PAYMENT_FAILED` | Payment attempt failed at the gateway |
| `REFUND_REQUESTED` | Refund request submitted |
| `REFUND_APPROVED` | Refund approved by the system |
| `REFUND_COMPLETED` | Refund amount transferred back to customer |
| `REFUND_REJECTED` | Refund request rejected (e.g., ceiling exceeded) |
| `DISPUTE_OPENED` | Chargeback dispute opened by issuer |
| `DISPUTE_ACCEPTED` | Merchant accepted the dispute |
| `DISPUTE_CHALLENGED` | Merchant submitted evidence to challenge |
| `DISPUTE_WON` | Merchant won the dispute |
| `DISPUTE_LOST` | Merchant lost the dispute |
| `RISK_PAYMENT_BLOCKED` | Payment blocked by risk engine before gateway dispatch |
| `RISK_DISPUTE_FLAGGED` | Merchant risk profile flagged due to dispute rate |

---

## Payload Schema

All events share a common envelope:

```json
{
  "eventId": "uuid-v4",
  "eventType": "PAYMENT_CAPTURED",
  "apiVersion": "2024-01-01",
  "createdAt": "2024-01-15T10:30:00Z",
  "merchantId": "uuid",
  "liveMode": true,
  "data": {
    "object": { ... }
  }
}
```

| Field | Description |
|---|---|
| `eventId` | Unique event UUID. Use for deduplication. |
| `eventType` | One of the event types listed above |
| `apiVersion` | API version at time of event creation |
| `createdAt` | UTC timestamp of event creation |
| `merchantId` | The merchant this event belongs to |
| `liveMode` | `true` for production events; `false` for test/sandbox |
| `data.object` | The full resource object at the time of the event |

---

## Example: PAYMENT_CAPTURED Payload

```json
{
  "eventId": "evt_01HXYZ123",
  "eventType": "PAYMENT_CAPTURED",
  "apiVersion": "2024-01-01",
  "createdAt": "2024-01-15T10:30:00Z",
  "merchantId": "mer_abc",
  "liveMode": true,
  "data": {
    "object": {
      "id": "pi_abc123",
      "invoiceId": "inv_xyz456",
      "merchantId": "mer_abc",
      "capturedAmount": 49900,
      "currency": "INR",
      "gateway": "RAZORPAY",
      "gatewayTransactionId": "pay_xxx",
      "status": "COMPLETED",
      "capturedAt": "2024-01-15T10:30:00Z"
    }
  }
}
```

**Amount fields are always in the smallest currency unit (paise for INR).** `49900` = ₹499.00

---

## Signature Verification

Every webhook delivery includes an HMAC-SHA256 signature header:

```
X-FirstClub-Signature: sha256=abc123def456...
```

**How to verify (example in Python):**
```python
import hmac
import hashlib

def verify_signature(payload_bytes: bytes, header_value: str, secret: str) -> bool:
    expected = "sha256=" + hmac.new(
        key=secret.encode("utf-8"),
        msg=payload_bytes,
        digestmod=hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(expected, header_value)
```

The `secret` is the webhook endpoint secret, set when registering the endpoint via:
```
POST /api/v2/merchants/{merchantId}/webhook-endpoints
```

**Important:** Use the raw request body bytes, not a parsed/re-serialized form. Field order matters for HMAC.

---

## Retry Policy

| Attempt | Delay after previous failure |
|---|---|
| 1 (initial) | Immediate |
| 2 | 1 minute |
| 3 | 5 minutes |
| 4 | 15 minutes |
| 5 | 1 hour |
| 6 | 2 hours |

- After **6 failed attempts**: delivery transitions to `GAVE_UP` (no further automatic retries).
- **Re-enable** endpoints or **retry individual deliveries** via the Admin Repair API.
- Merchants should respond within **5 seconds**. Slower responses are treated as failures.

### Auto-disable mechanisms

Two independent guards can deactivate an endpoint automatically:

| Mechanism | Trigger | Field set |
|---|---|---|
| **Consecutive-failure threshold** | 5 non-2xx dispatch results _in a row_ | `autoDisabledAt` stamped, `active=false` |
| **GAVE_UP accumulation** | 5 deliveries reach `GAVE_UP` for the same endpoint | `active=false` (secondary guard) |

When `autoDisabledAt` is set:
- New events are **not enqueued** for this endpoint until it is re-enabled.
- The field is cleared (set to `null`) only by the re-enable API.
- A successful dispatch **resets** the consecutive-failure counter to 0.

---

## Endpoint Management

### Register an endpoint

```
POST /api/v2/merchants/{merchantId}/webhook-endpoints
Content-Type: application/json

{
  "url": "https://your-domain.com/webhooks/firstclub",
  "subscribedEvents": ["PAYMENT_CAPTURED", "REFUND_COMPLETED"],
  "secret": "whsec_your_secret_here"
}
```

### List endpoints

```
GET /api/v2/merchants/{merchantId}/webhook-endpoints
```

### Update an endpoint

```
PUT /api/v2/merchants/{merchantId}/webhook-endpoints/{endpointId}
```

### Deactivate an endpoint (soft-delete)

```
DELETE /api/v2/merchants/{merchantId}/webhook-endpoints/{endpointId}
```

### Re-enable a deactivated or auto-disabled endpoint

Resets `active=true`, `autoDisabledAt=null`, and `consecutiveFailures=0`.

```
PATCH /api/v2/merchants/{merchantId}/webhook-endpoints/{endpointId}/reenable
```

Response: `204 No Content`

### Send a test ping

Dispatches a synthetic `webhook.ping` event **immediately** to verify the endpoint is reachable.
The ping does **not** count towards the consecutive-failure counter.

```
POST /api/v2/merchants/{merchantId}/webhook-endpoints/{endpointId}/ping
```

Response `200 OK`:
```json
{
  "deliveryId": 1042,
  "endpointId": 10,
  "status": "DELIVERED",
  "message": "Ping delivered successfully"
}
```

The ping payload looks like:
```json
{
  "eventId": "uuid-v4",
  "eventType": "webhook.ping",
  "merchantId": 1,
  "endpointId": 10,
  "createdAt": "2024-06-01T12:00:00"
}
```

---

## Delivery History & Search

### List all deliveries

```
GET /api/v2/merchants/{merchantId}/webhook-deliveries
```

### Get a single delivery

```
GET /api/v2/merchants/{merchantId}/webhook-deliveries/{deliveryId}
```

### Search deliveries with filters

All parameters are optional. Results are sorted newest-first. Maximum 500 results per request.

```
GET /api/v2/merchants/{merchantId}/webhook-deliveries/search
  ?eventType=invoice.paid
  &status=FAILED
  &responseCode=503
  &from=2024-06-01T00:00:00
  &to=2024-06-30T23:59:59
  &limit=100
```

| Parameter | Type | Description |
|---|---|---|
| `eventType` | string | Filter by exact event type |
| `status` | string | `PENDING`, `DELIVERED`, `FAILED`, or `GAVE_UP` |
| `responseCode` | integer | Filter by last HTTP response code |
| `from` | ISO-8601 datetime | Lower bound on `createdAt` |
| `to` | ISO-8601 datetime | Upper bound on `createdAt` |
| `limit` | integer | Max results (default 50, max 500) |

---

## Idempotent Enqueue

The platform computes a SHA-256 fingerprint of `(endpointId, eventType, payload)` for each scheduled
delivery. If a delivery with the **same fingerprint** already exists in `DELIVERED` state, the new
enqueue is silently skipped. This prevents duplicate delivery on event replay or retry storms.

Merchants should still handle duplicates (using the `eventId` field), because idempotency at the DB
level does not cover all edge cases (e.g., the acknowledgement from the merchant endpoint was lost
before the delivery row was updated).


### Test an endpoint (sends a synthetic ping event)

```
POST /api/v1/merchants/{merchantId}/webhooks/{endpointId}/test
```

---

## Delivery History

```
GET /api/v1/merchants/{merchantId}/webhooks/{endpointId}/deliveries
```

Returns all delivery attempts with status (`SUCCESS`, `FAILED`, `PENDING`, `DLQ`), HTTP response code, latency, and timestamp.

---

## Idempotency for Consumers

The merchant's webhook consumer should:
1. Extract `eventId` from the payload
2. Check if `eventId` has already been processed (DB or cache)
3. If already processed → return `200 OK` immediately without processing again
4. If new → process → mark as processed → return `200 OK`

The platform will deliver the same event multiple times on retry; the consumer must be idempotent.

---

## Platform-Level Inbound Deduplication (Phase 6)

The platform runs a **two-tier deduplication layer** on inbound gateway webhooks
(`POST /api/v1/webhooks/gateway`) before any business logic is applied.

### Tier 1 — Redis fast path

On every inbound request the platform attempts a Redis `SET NX EX` on:

```
{env}:firstclub:dedup:webhook:{provider}:{eventId}    TTL = 3600 s
```

If the key already exists the request returns `200 OK` (DUPLICATE) immediately —
no DB query, no signature verification, no state mutation.

If the gateway omits `eventId` (rare, but observed in some fault-tolerant re-delivery
scenarios), the platform falls back to a SHA-256 hash of the raw payload body:

```
{env}:firstclub:dedup:webhookfp:{provider}:{payloadHash}    TTL = 300 s
```

### Tier 2 — DB authoritative check

If the Redis fast path passes, the DB is consulted:
- A row in `webhook_events` with `event_id = {eventId}` **and** `processed = true`
  is treated as a duplicate.
- If no such row exists (event is genuinely new) the row is created and processing proceeds.

### Post-processing Redis seeding

After a successful processing cycle, `WebhookDedupService.recordWebhookReceived(provider, eventId)`
seeds the Redis key regardless of whether it was set earlier — ensuring future retries hit
the fast path even after a Redis failover.

### Business-effect dedup (independent layer)

Payment captures, refunds, and settlement posting apply an additional content-addressable
fingerprint check via `BusinessEffectDedupService`:

```
{env}:firstclub:dedup:biz:{effectType}:{sha256Fingerprint}    TTL = 86400 s
```

The fingerprint is computed only from business-meaningful fields (not timestamps or
generated IDs), making it stable across retries.  The durable fallback is the
`business_effect_fingerprints` table with a `UNIQUE(effect_type, fingerprint)` constraint.

### Admin diagnostics

| Endpoint | Description |
|---|---|
| `GET /api/v2/admin/webhooks/dedup/{provider}/{eventId}` | Returns Redis marker presence + DB processed status for a specific event |
| `GET /api/v2/admin/dedup/business-effects?effectType={type}&since={iso8601}` | Lists recently recorded business-effect fingerprints |

### Replay guarantees

| Scenario | Result |
|---|---|
| Same `eventId` delivered twice within 1 h | First: PROCESSED. Second: Redis fast-path DUPLICATE. |
| Same `eventId` delivered after Redis failover | DB row check → DUPLICATE if already processed. |
| Identical payload, different `eventId` | Payload-hash dedup catches it within 5 min window. |
| Different payload, different `eventId` | Both processed normally. |
| `eventId` absent (malformed gateway event) | Payload-hash check applied; returns DUPLICATE if same bytes seen within 5 min. |
