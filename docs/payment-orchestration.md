# Payment Orchestration — Payment Intents V2 & Attempts

**Owner:** Payments Domain  
**Version:** Phase 6  
**Base path:** `/api/v2/merchants/{merchantId}/payment-intents`

---

## Overview

Payment Intents V2 is the central orchestration primitive for executing a payment. An intent
captures **what** the platform intends to charge (amount, currency, customer, optional invoice/
subscription linkage) while a **payment attempt** tracks one discrete call to an external gateway
to fulfil that intent.

The design follows the Stripe-style state machine: a single intent can have multiple attempts
(retries after retriable failures), and every mutation endpoint is idempotency-key aware.

---

## Database Schema

### `payment_intents_v2`

| Column            | Type              | Nullable | Notes                                         |
|-------------------|-------------------|----------|-----------------------------------------------|
| id                | BIGSERIAL PK      | N        |                                               |
| merchant_id       | BIGINT FK         | N        | `merchant_accounts.id`                        |
| customer_id       | BIGINT FK         | N        | `customers.id`                                |
| invoice_id        | BIGINT FK         | Y        | `invoices.id` — links intent to a bill        |
| subscription_id   | BIGINT FK         | Y        | `subscriptions_v2.id`                         |
| payment_method_id | BIGINT FK         | Y        | `payment_methods.id`                          |
| amount            | DECIMAL(18,4)     | N        |                                               |
| currency          | VARCHAR(10)       | N        | ISO 4217 (e.g. `INR`, `USD`)                  |
| status            | VARCHAR(32)       | N        | See state machine below; default `REQUIRES_PAYMENT_METHOD` |
| capture_mode      | VARCHAR(16)       | N        | `AUTO` or `MANUAL`; default `AUTO`            |
| client_secret     | VARCHAR(128) UNIQ | N        | 128-char opaque token (2× UUID, no dashes)    |
| idempotency_key   | VARCHAR(128)      | Y        | Caller-supplied; scoped to merchant           |
| metadata_json     | TEXT              | Y        | Arbitrary JSON                                |
| version           | BIGINT            | N        | Optimistic-locking `@Version`; default 0      |
| created_at        | TIMESTAMP         | N        |                                               |
| updated_at        | TIMESTAMP         | N        |                                               |

### `payment_attempts`

| Column             | Type         | Nullable | Notes                                         |
|--------------------|--------------|----------|-----------------------------------------------|
| id                 | BIGSERIAL PK | N        |                                               |
| payment_intent_id  | BIGINT FK    | N        | `payment_intents_v2.id`                       |
| attempt_number     | INT          | N        | Monotonic per-intent counter (1, 2, 3 …)      |
| gateway_name       | VARCHAR(64)  | N        | e.g. `razorpay`, `stripe`                     |
| gateway_reference  | VARCHAR(128) | Y        | Gateway's own tx/auth reference               |
| request_hash       | VARCHAR(128) | Y        | Hash of the outbound request for audit        |
| response_code      | VARCHAR(64)  | Y        | Gateway response code                         |
| response_message   | TEXT         | Y        | Human-readable gateway message                |
| latency_ms         | BIGINT       | Y        | Round-trip to gateway in milliseconds         |
| status             | VARCHAR(32)  | N        | See attempt status machine below; default `STARTED` |
| failure_category   | VARCHAR(32)  | Y        | One of `FailureCategory` values               |
| retriable          | BOOLEAN      | N        | Whether a new attempt may be spawned; default `false` |
| created_at         | TIMESTAMP    | N        |                                               |
| completed_at       | TIMESTAMP    | Y        | Set when attempt reaches a terminal state     |
| **UNIQUE** (payment_intent_id, attempt_number) | | | Prevents duplicate attempt rows |

---

## State Machines

### Payment Intent Status

```
REQUIRES_PAYMENT_METHOD ──(attach PM)──────► REQUIRES_CONFIRMATION
          │                                           │
          └────────────(confirm + PM)─────────────────┘
                                                      │
                                                      ▼
                                                  PROCESSING
                                                 /    │    \
                                         SUCCEEDED  FAILED  REQUIRES_ACTION
```

| Status                   | `allowsConfirm()` | `allowsCancel()` | `isTerminal()` |
|--------------------------|:-----------------:|:----------------:|:--------------:|
| REQUIRES_PAYMENT_METHOD  | ✓                 | ✓                |                |
| REQUIRES_CONFIRMATION    | ✓                 | ✓                |                |
| PROCESSING               |                   |                  |                |
| REQUIRES_ACTION          |                   | ✓                |                |
| SUCCEEDED                |                   |                  | ✓              |
| FAILED                   | ✓ (if retriable)  |                  | ✓              |
| CANCELLED                |                   |                  | ✓              |

> **FAILED + retriable**: if the most recent attempt has `retriable = true`, a new confirm call
> is permitted, spawning attempt N+1. If `retriable = false`, the intent is permanently stuck in
> FAILED and the caller must create a new intent.

### Payment Attempt Status

```
STARTED ──► AUTHORIZED ──► CAPTURED   (terminal)
       \──► FAILED                    (terminal)
        \──► TIMEOUT                  (terminal)
         \──► REQUIRES_ACTION
```

| Status          | `isTerminal()` |
|-----------------|:--------------:|
| STARTED         |                |
| AUTHORIZED      |                |
| CAPTURED        | ✓              |
| FAILED          | ✓              |
| TIMEOUT         | ✓              |
| REQUIRES_ACTION |                |

Attempts are **immutable once terminal** — calling any mark-* method on a terminal attempt
throws `ATTEMPT_IMMUTABLE (409)`.

---

## Enums

### `CaptureMode`
- `AUTO` — capture immediately on authorization
- `MANUAL` — separate capture step (useful for pre-authorization flows)

### `FailureCategory`
| Value           | `isTypicallyRetriable()` | Description                        |
|-----------------|:------------------------:|------------------------------------|
| NETWORK         | ✓                        | Transient network / timeout error  |
| ISSUER_DECLINE  |                          | Card issuer hard-declined          |
| RISK_BLOCK      |                          | Fraud / risk rules blocked         |
| GATEWAY_ERROR   | ✓                        | Gateway internal error             |
| CUSTOMER_ABORT  |                          | Customer cancelled 3DS / redirect  |
| UNKNOWN         |                          | Unknown / unclassified failure     |

---

## API Reference

All endpoints require `Authorization: Bearer <JWT>` with role `ADMIN`.

### POST `/api/v2/merchants/{merchantId}/payment-intents`
Create a new payment intent.

**Headers**
- `Idempotency-Key` *(optional but recommended)* — if a previous request with the same key exists
  for this merchant, the original intent is returned without creating a new one.

**Request body** (`PaymentIntentCreateRequestDTO`)
```json
{
  "customerId": 5,
  "invoiceId": null,
  "subscriptionId": null,
  "amount": "1500.00",
  "currency": "INR",
  "paymentMethodId": 10,
  "captureMode": "AUTO",
  "metadataJson": null
}
```

**Response** `201 Created` → `PaymentIntentV2ResponseDTO`

| Field           | Notes                                                      |
|-----------------|------------------------------------------------------------|
| status          | `REQUIRES_CONFIRMATION` if PM attached, otherwise `REQUIRES_PAYMENT_METHOD` |
| clientSecret    | 128-char token; pass to client SDK for front-end flows     |
| version         | Optimistic-lock version; include in `confirm` / `cancel`   |

---

### GET `/api/v2/merchants/{merchantId}/payment-intents/{paymentIntentId}`
Fetch a payment intent. Returns `404` if the intent doesn't belong to the merchant.

---

### POST `/api/v2/merchants/{merchantId}/payment-intents/{paymentIntentId}/confirm`
Confirm an intent: attach a payment method (if not already set), spawn a new gateway attempt,
and drive the intent to `SUCCEEDED` or `FAILED`.

**Headers**
- `Idempotency-Key` *(optional)* — safe to retry; if the intent is already `SUCCEEDED` the
  current snapshot is returned without spawning another attempt.

**Request body** (`PaymentIntentConfirmRequestDTO`)
```json
{
  "paymentMethodId": null,
  "gatewayName": "razorpay",
  "attemptMetadata": null
}
```

**Error codes**
| Code                            | HTTP | Cause                                           |
|---------------------------------|------|-------------------------------------------------|
| `MISSING_PAYMENT_METHOD`        | 422  | No PM on intent and none supplied in request    |
| `INVALID_PAYMENT_INTENT_TRANSITION` | 422 | Intent is in CANCELLED or other non-confirmable state |
| `NON_RETRIABLE_FAILURE`         | 422  | Last attempt failed with `retriable=false`      |

---

### POST `/api/v2/merchants/{merchantId}/payment-intents/{paymentIntentId}/cancel`
Cancel an intent that is still in a cancellable state
(`REQUIRES_PAYMENT_METHOD`, `REQUIRES_CONFIRMATION`, `REQUIRES_ACTION`).

Returns `422` with `INVALID_PAYMENT_INTENT_TRANSITION` if the intent is already terminal.

---

### GET `/api/v2/merchants/{merchantId}/payment-intents/{paymentIntentId}/attempts`
Return all gateway attempts for the intent, ordered by `attempt_number ASC`.

---

## Idempotency

| Endpoint    | Idempotency mechanism                                              |
|-------------|--------------------------------------------------------------------|
| **Create**  | `idempotency_key` stored on intent; lookup before insert           |
| **Confirm** | If intent is `SUCCEEDED`, return current snapshot without new attempt |
| **Cancel**  | Idempotent by nature of state-machine; cancelling an already-cancelled intent returns `422` |

---

## Security & Tenant Isolation

All lookups use `findByMerchantIdAndId(merchantId, intentId)`. An intent belonging to merchant A
is never visible or mutable via merchant B's scoped URLs.

---

## Optimistic Locking

`PaymentIntentV2.version` is annotated `@Version` (JPA optimistic locking). Concurrent mutations
to the same intent will result in a `ObjectOptimisticLockingFailureException` at the database
layer, which prevents double-charges in concurrent confirm scenarios.

---

## Implementation Notes

- **Gateway simulation** — Phase 6 ships with a deterministic stub (`simulateGateway`) that always
  returns `SUCCEEDED`. Override `PaymentIntentV2ServiceImpl.simulateGateway` or inject a gateway
  adapter in Phase 7 to wire real providers.
- **Capture flow** — `CaptureMode.AUTO` captures immediately; `MANUAL` will use a separate
  `/capture` endpoint (planned Phase 7).
- **clientSecret** — concatenation of two random UUIDs with dashes stripped, giving 64 characters.
  Used by front-end SDKs to verify the intent client-side.
