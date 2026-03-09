# Payments Module

## Overview

The payments module implements a **Stripe-like PaymentIntent lifecycle** on top of
the FirstClub Membership platform.  It ships with:

| Component | Description |
|---|---|
| `PaymentIntent` | Represents a single payment attempt (state machine driven) |
| `Payment` | Immutable record of a completed/failed charge |
| `WebhookEvent` | Idempotent log of every inbound gateway event |
| `DeadLetterMessage` | Audit trail for permanently failed processing |
| Fake Gateway | In-process emulator for local development & testing |
| Webhook Retry Job | Background retrier with exponential back-off |

---

## PaymentIntent Lifecycle

```
REQUIRES_PAYMENT_METHOD
        │
        ▼
REQUIRES_CONFIRMATION ──────────────────────────────────────┐
        │                                                   │
        ├──── REQUIRES_ACTION ──── (OTP confirm) ──────────►│
        │           │                                       │
        │           └─── FAILED                            │
        ▼                                                   │
    PROCESSING ◄────────────────────────────────────────────┘
        │
        ├──── SUCCEEDED  (terminal)
        ├──── FAILED     (terminal; can retry → REQUIRES_PAYMENT_METHOD)
        └──── REQUIRES_ACTION  (3-DS loop)
```

The `PaymentIntentService` helpers `markProcessing()` and `markRequiresAction()`
automatically advance through `REQUIRES_CONFIRMATION` when starting from
`REQUIRES_PAYMENT_METHOD`, so callers do not have to manage the intermediate step.

---

## Database Schema (V6)

### `payment_intents`

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL PK` | |
| `invoice_id` | `BIGINT NULL` | Optional link to a billing invoice |
| `amount` | `DECIMAL(10,2)` | |
| `currency` | `VARCHAR(10)` | Default `INR` |
| `status` | `VARCHAR(40)` | Maps to `PaymentIntentStatus` enum |
| `client_secret` | `VARCHAR(64) UNIQUE` | Opaque token for front-end confirmation |
| `gateway_reference` | `VARCHAR(64) UNIQUE` | Opaque token for gateway correlation |
| `created_at` / `updated_at` | `TIMESTAMP` | |

### `payments`

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL PK` | |
| `payment_intent_id` | `BIGINT FK` | References `payment_intents` |
| `amount` / `currency` | | Copied from the intent at capture time |
| `status` | `VARCHAR(40)` | `CAPTURED` or `FAILED` |
| `gateway_txn_id` | `VARCHAR(64) UNIQUE` | Gateway-assigned transaction identifier |
| `captured_at` | `TIMESTAMP NULL` | Set for CAPTURED rows |

### `webhook_events`

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL PK` | |
| `event_id` | `VARCHAR(64) UNIQUE` | Idempotency key — gates duplicate processing |
| `event_type` | `VARCHAR(64)` | e.g. `PAYMENT_INTENT.SUCCEEDED` |
| `payload` | `TEXT` | Raw JSON body |
| `signature_valid` | `BOOLEAN` | Result of HMAC-SHA256 check |
| `processed` | `BOOLEAN` | `true` after successful processing |
| `attempts` | `INT` | Number of processing attempts |
| `next_attempt_at` | `TIMESTAMP` | Earliest retry window |
| `last_error` | `TEXT NULL` | Last exception message |

### `dead_letter_messages`

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL PK` | |
| `source` | `VARCHAR(32)` | e.g. `WEBHOOK` |
| `payload` | `TEXT` | Original failing payload |
| `error` | `TEXT` | Exception message |
| `created_at` | `TIMESTAMP` | |

---

## Webhook Signature Verification

All events are signed with **HMAC-SHA256**.

```
X-Signature: hex( hmac_sha256( secret, raw_request_body ) )
```

The secret is configured via:

```properties
payments.webhook.secret=<rotate-in-production>
```

`WebhookSignatureService.verify(payload, signature)` returns `false` for any
mismatch.  Events with an invalid signature are stored in `webhook_events` with
`signature_valid=false` and are **never retried**.

---

## Fake Gateway Emulator

Two endpoints are available for local development and integration testing.
**Remove them (or gate behind a feature flag) before production deployment.**

### `POST /gateway/pay`

Simulates an initial charge attempt.

**Request:**

```json
{
  "paymentIntentId": 1,
  "outcome": "SUCCEEDED"
}
```

`outcome` must be one of `SUCCEEDED`, `FAILED`, or `REQUIRES_ACTION`.

**Behaviour:**

| Outcome | Immediate state change | Async webhook (2-5s) |
|---|---|---|
| `SUCCEEDED` | PI → PROCESSING | `PAYMENT_INTENT.SUCCEEDED` |
| `FAILED` | PI → PROCESSING | `PAYMENT_INTENT.FAILED` |
| `REQUIRES_ACTION` | PI → REQUIRES_ACTION | _none_ — fires after OTP confirm |

**Response (202 Accepted):**

```json
{
  "paymentIntentId": 1,
  "status": "PROCESSING",
  "message": "Payment is being processed — await SUCCEEDED webhook"
}
```

---

### `POST /gateway/otp/confirm`

Simulates a successful 3-DS / OTP completion.

**Request:**

```json
{
  "paymentIntentId": 1
}
```

**Behaviour:** Moves the PI from `REQUIRES_ACTION` → `PROCESSING` and schedules
a `PAYMENT_INTENT.SUCCEEDED` webhook.

---

## Webhook Receiver

### `POST /api/v1/webhooks/gateway`

Receives a signed event from the gateway.

**Headers:**

```
Content-Type: application/json
X-Signature: <hex-hmac>
```

**Payload (example):**

```json
{
  "eventId": "evt_a1b2c3d4e5f6g7h8i9j0",
  "eventType": "PAYMENT_INTENT.SUCCEEDED",
  "paymentIntentId": 1,
  "amount": 999.00,
  "currency": "INR",
  "gatewayTxnId": "gwtxn_a1b2c3d4e5f6g7h8i9j0",
  "timestamp": "2025-01-01T12:00:00"
}
```

**Response codes:**

| Condition | HTTP |
|---|---|
| Processed or duplicate event | `200 OK` |
| Invalid HMAC signature | `401 Unauthorized` |
| Internal processing error | `500 Internal Server Error` |

---

## Webhook Retry & Dead Letter

`WebhookRetryJob` runs every **30 seconds** and picks up events matching:

- `processed = false`
- `signature_valid = true`
- `next_attempt_at <= now`
- `attempts < 5`

Backoff formula: `min(3600, 2^attempts)` seconds.

After **5 failed attempts** the event stops being eligible for automatic retry.
Every failure additionally writes a row to `dead_letter_messages` for manual
inspection.

---

## Testing

### Unit tests

```bash
mvn test -Dtest=WebhookSignatureServiceTest
```

Verifies HMAC consistency, signature verification, tampered-payload rejection,
and null-safety — no Spring context required.

### Integration tests (requires Docker)

```bash
mvn test -Dtest=PaymentIntegrationTest
```

Covers:

1. **Full happy-path** — create PI → `/gateway/pay` SUCCEEDED → poll → assert SUCCEEDED + CAPTURED payment
2. **3-DS / OTP flow** — REQUIRES_ACTION → `/gateway/otp/confirm` → poll → assert SUCCEEDED
3. **Duplicate idempotency** — same `event_id` posted twice → only one `Payment` row created

---

## Configuration Reference

```properties
# Payments — HMAC secret for webhook signature verification
payments.webhook.secret=dev-only-webhook-secret-change-in-prod
```

Rotate the secret in production via an environment variable:

```bash
export PAYMENTS_WEBHOOK_SECRET=<strong-random-secret>
```

Spring Boot maps `PAYMENTS_WEBHOOK_SECRET` → `payments.webhook.secret` automatically.
