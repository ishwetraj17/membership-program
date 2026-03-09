# Billing Module

## Overview

The billing module handles all money-related lifecycle events for memberships: invoice creation, proration when plans change mid-cycle, credit-note management, and payment-to-subscription reconciliation.

```
com.firstclub.billing
├── dto/           — API-facing data transfer objects
├── entity/        — JPA entities (Invoice, InvoiceLine, CreditNote)
├── model/         — Enums (InvoiceStatus)
├── repository/    — Spring Data JPA repositories
└── service/       — Business logic
    ├── InvoiceService
    ├── ProrationCalculator
    └── BillingSubscriptionService
```

---

## Database Schema (V7\_\_billing.sql)

### `invoices`

| Column           | Type                         | Notes                                               |
|------------------|------------------------------|-----------------------------------------------------|
| id               | BIGSERIAL PK                 |                                                     |
| user_id          | BIGINT FK → users            | NOT NULL                                            |
| subscription_id  | BIGINT FK → subscriptions    | nullable — can invoice without an active sub        |
| status           | VARCHAR(20)                  | DRAFT · OPEN · PAID · VOID · UNCOLLECTIBLE          |
| currency         | VARCHAR(3)                   | default `INR`                                       |
| total_amount     | NUMERIC(12,2)                | recomputed from lines; clamped to ≥ 0               |
| due_date         | TIMESTAMP                    |                                                     |
| period_start     | TIMESTAMP                    | billing period start                                |
| period_end       | TIMESTAMP                    | billing period end                                  |
| created_at       | TIMESTAMP                    | set by `@CreationTimestamp`                         |
| updated_at       | TIMESTAMP                    | set by `@UpdateTimestamp`                           |

### `invoice_lines`

| Column      | Type           | Notes                                         |
|-------------|----------------|-----------------------------------------------|
| id          | BIGSERIAL PK   |                                               |
| invoice_id  | BIGINT FK      | NOT NULL                                      |
| line_type   | VARCHAR(30)    | PLAN_CHARGE · PRORATION · TAX · DISCOUNT · CREDIT_APPLIED |
| description | TEXT           |                                               |
| amount      | NUMERIC(12,2)  | positive = charge; negative = credit/discount |

### `credit_notes`

| Column      | Type           | Notes                                         |
|-------------|----------------|-----------------------------------------------|
| id          | BIGSERIAL PK   |                                               |
| user_id     | BIGINT FK      | NOT NULL                                      |
| currency    | VARCHAR(3)     | default `INR`                                 |
| amount      | NUMERIC(12,2)  | total credit issued                           |
| reason      | TEXT           |                                               |
| created_at  | TIMESTAMP      |                                               |
| used_amount | NUMERIC(12,2)  | default 0 — amount already applied to invoices|

`CreditNote.getAvailableBalance()` is a `@Transient` helper = `amount - usedAmount`.

---

## Key Services

### `InvoiceService`

| Method | Description |
|--------|-------------|
| `createInvoiceForSubscription(userId, subscriptionId, planId, periodStart, periodEnd)` | Creates an OPEN invoice with a `PLAN_CHARGE` line, applies available credits, recomputes the total. Returns `InvoiceDTO`. |
| `applyAvailableCredits(userId, invoiceId)` | FIFO-applies credit notes to an OPEN invoice, adding `CREDIT_APPLIED` lines and updating `credit_notes.used_amount`. |
| `onPaymentSucceeded(invoiceId)` | Idempotent hook called by the webhook processor. Transitions invoice `OPEN → PAID` and activates the linked subscription (`PENDING → ACTIVE`). |
| `findById(invoiceId)` | Returns `InvoiceDTO` with all lines. |
| `findByUserId(userId)` | Returns list of `InvoiceDTO` for a user. |

### `ProrationCalculator`

| Method | Description |
|--------|-------------|
| `compute(currentPlanPrice, totalDays, remainingDays, newPlanPrice)` | **Pure** — no DB access. Returns ordered `[PRORATION credit (if any), PLAN_CHARGE]` lines. Safe to call from unit tests with `null` repositories. |
| `preview(subscriptionId, newPlanId)` | Loads subscription + plan from DB, delegates to `compute`, returns `ProratedPreviewResponse`. |

**Proration algorithm:**

```
credit = -(currentPlanPrice × remainingDays / totalDays)   # negative, rounds HALF_UP to 2dp
charge = newPlanPrice
total  = credit + charge
amountDue = max(0, total)
```

### `BillingSubscriptionService`

Orchestrates the V2 subscription creation flow:

1. Create `Subscription` with status `PENDING`
2. Record `SubscriptionHistory(CREATED)`
3. Create OPEN invoice via `InvoiceService.createInvoiceForSubscription()`
4. Apply any credit-note balance
5. **If `amountDue == 0`** → call `invoiceService.onPaymentSucceeded()` immediately; return `paymentIntentId = null`
6. **If `amountDue > 0`** → create `PaymentIntent` via `PaymentIntentService.createForInvoice()`; return `clientSecret` for front-end confirmation

---

## REST Endpoints

### `POST /api/v2/subscriptions`

Creates a new V2 subscription with payment orchestration.

**Headers**

| Header            | Required | Description                               |
|-------------------|----------|-------------------------------------------|
| `Idempotency-Key` | Yes      | Client UUID (max 80 chars). Safe to retry. |
| `Authorization`   | Yes      | Bearer JWT                                |

**Request body** — `SubscriptionRequestDTO`

```json
{
  "userId": 42,
  "planId": 7,
  "autoRenewal": true
}
```

**Response 201** — `SubscriptionV2Response`

```json
{
  "subscriptionId": 101,
  "invoiceId": 55,
  "paymentIntentId": 12,
  "clientSecret": "pi_1234_secret_abcd",
  "amountDue": 999.00,
  "currency": "INR",
  "status": "PENDING"
}
```

When credits cover the full amount, `paymentIntentId` and `clientSecret` are `null` and `status` is `ACTIVE`.

---

### `GET /api/v2/subscriptions/{id}/change-preview`

Returns a dry-run proration breakdown for switching an existing subscription to a new plan **today**.

**Query parameters**

| Param      | Type | Description       |
|------------|------|-------------------|
| `newPlanId`| Long | Target plan ID    |

**Response 200** — `ProratedPreviewResponse`

```json
{
  "currentPlanId": 7,
  "newPlanId": 9,
  "lines": [
    { "lineType": "PRORATION",    "description": "Credit for 15 unused day(s) on current plan", "amount": -600.00 },
    { "lineType": "PLAN_CHARGE",  "description": "New plan charge",                              "amount": 2400.00 }
  ],
  "total": 1800.00,
  "amountDue": 1800.00
}
```

`amountDue` is always `max(0, total)` — it cannot be negative.

---

## Webhook Integration

When the gateway fires a `PAYMENT_INTENT.SUCCEEDED` event, `WebhookProcessingService.handleSucceeded()` now calls `InvoiceService.onPaymentSucceeded(invoiceId)` if the PaymentIntent has a linked invoice:

```
WebhookProcessingService.handleSucceeded()
  └─ invoiceService.onPaymentSucceeded(pi.invoiceId)
       ├─ invoice OPEN → PAID (state-machine validated)
       └─ subscription PENDING → ACTIVE (state-machine validated)
            └─ SubscriptionHistory(PAYMENT_SUCCEEDED) saved
```

The call is wrapped in a `try/catch` that re-throws on failure so the `@Transactional` boundary rolls back all writes (PI mark-succeeded, Payment row, invoice, subscription) and the webhook event remains pending for the retry job.

---

## State Transitions

### Invoice

```
DRAFT → OPEN → PAID
             → VOID
             → UNCOLLECTIBLE
```

### Subscription (billing-relevant transitions)

```
PENDING → ACTIVE       (payment received)
PENDING → CANCELLED    (payment timeout / explicit cancel)
```

---

## Credit Notes

Credit notes represent wallet balances pre-loaded for a user (e.g. refunds, promotional credits).

They are applied **FIFO** (oldest available first) by `InvoiceService.applyAvailableCredits()`:

1. Fetch all credit notes for the user where `used_amount < amount` ordered by `created_at`.
2. For each note, compute the lesser of `remaining invoice balance` and `available balance`.
3. Add a `CREDIT_APPLIED` line (negative) on the invoice.
4. Update `credit_notes.used_amount`.
5. Recompute `invoice.total_amount` from all lines (clamped to ≥ 0).

---

## Testing

| Test class | Type | What it covers |
|---|---|---|
| `ProrationCalculatorTest` | Unit (no Spring) | `compute()` for half-period, zero-days, full-period, over-credit, and negative-days scenarios |
| `BillingIntegrationTest` | Integration (Testcontainers) | Full webhook flow (PENDING→ACTIVE, invoice PAID); credit-note full-cover (immediate activation, null paymentIntentId) |

Run all tests:

```bash
mvn test
```

> Integration tests are skipped automatically when Docker is unavailable (`@Testcontainers(disabledWithoutDocker = true)`).
