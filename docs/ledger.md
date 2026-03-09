# Ledger Module — Double-Entry Accounting

## Overview

The Ledger module implements a double-entry bookkeeping system integrated with the Payments and Refunds workflows. Every financial event is recorded as a balanced set of DEBIT and CREDIT lines, ensuring the accounting equation always holds.

## Schema

### `ledger_accounts`
Core accounts seeded on startup. Each account has a `account_type` that defines its normal balance.

| Name                    | Type       | Normal Balance |
|-------------------------|------------|----------------|
| `PG_CLEARING`           | ASSET      | DEBIT          |
| `BANK`                  | ASSET      | DEBIT          |
| `SUBSCRIPTION_LIABILITY`| LIABILITY  | CREDIT         |
| `REVENUE_SUBSCRIPTIONS` | INCOME     | CREDIT         |

### `ledger_entries`
One row per financial event. Contains the event type, reference (what triggered it), currency, and optional metadata.

**Entry Types:** `PAYMENT_CAPTURED`, `REFUND_ISSUED`, `REVENUE_RECOGNIZED`, `SETTLEMENT`

**Reference Types:** `PAYMENT`, `REFUND`, `INVOICE`, `SUBSCRIPTION`, `SETTLEMENT_BATCH`

### `ledger_lines`
Two or more rows per entry. Each line debits or credits a specific account by a positive amount.

**Invariant**: For every entry, `SUM(lines where direction=DEBIT) == SUM(lines where direction=CREDIT)`.

### `refunds`
Tracks refund records tied to a `payments.id`. Status is set to `COMPLETED` immediately upon creation.

---

## Accounting Journal

### Payment Captured (`PAYMENT_CAPTURED`)

When the gateway fires a `PAYMENT_INTENT.SUCCEEDED` webhook:

```
DR  PG_CLEARING              ₹amount
    CR  SUBSCRIPTION_LIABILITY       ₹amount
```

*PG_CLEARING* represents funds confirmed by the payment gateway but not yet settled to the bank.

### Refund Issued (`REFUND_ISSUED`)

When `POST /api/v2/refunds` is called:

```
DR  SUBSCRIPTION_LIABILITY   ₹amount
    CR  PG_CLEARING                  ₹amount
```

This reverses the liability obligation back to the clearing balance.

---

## API

### Issue a Refund

```
POST /api/v2/refunds
Idempotency-Key: <uuid>
Content-Type: application/json

{
  "paymentId": 42,
  "amount": "300.00",
  "reason": "Customer request"
}
```

| Validation | Behaviour |
|---|---|
| `paymentId` not found | `404 PAYMENT_NOT_FOUND` |
| Payment not in `CAPTURED` state | `400 PAYMENT_NOT_CAPTURED` |
| Refund amount > payment amount | `400 REFUND_AMOUNT_EXCEEDS_PAYMENT` |

**Response (201):**
```json
{
  "id": 1,
  "paymentId": 42,
  "amount": "300.00",
  "currency": "INR",
  "reason": "Customer request",
  "status": "COMPLETED",
  "createdAt": "2025-01-01T12:00:00"
}
```

### Get Ledger Balances (Admin only)

```
GET /api/v1/admin/ledger/balances
Authorization: Bearer <admin-token>
```

**Response (200):**
```json
[
  {
    "accountName": "PG_CLEARING",
    "accountType": "ASSET",
    "currency": "INR",
    "debitTotal": "1000.00",
    "creditTotal": "300.00",
    "balance": "700.00"
  },
  {
    "accountName": "SUBSCRIPTION_LIABILITY",
    "accountType": "LIABILITY",
    "currency": "INR",
    "debitTotal": "300.00",
    "creditTotal": "1000.00",
    "balance": "700.00"
  }
]
```

Balance formula:
- **ASSET / EXPENSE**: `balance = debitTotal - creditTotal`
- **LIABILITY / INCOME**: `balance = creditTotal - debitTotal`

---

## Idempotency

- **Payment webhook**: The `ledgerService.postEntry()` call is placed inside the `if (!existsByGatewayTxnId)` block, so it executes atomically with Payment creation. Re-delivered webhooks skip both.
- **Refund endpoint**: Protected by `@Idempotent(ttlHours=24)`. Supplying the same `Idempotency-Key` header within 24 hours returns the cached response without creating a duplicate refund or ledger entry.

---

## Metrics

| Metric | Type | Description |
|---|---|---|
| `ledger_unbalanced_total` | Counter | Incremented whenever `postEntry()` rejects an unbalanced set of lines. Should always be 0 in production. |

---

## Design Notes

- **No JPA relationships** between `ledger_lines → ledger_accounts` at the ORM level — accounts are resolved by name at write time and stored as plain `accountId` longs.
- **Balance aggregation in Java** — `getBalances()` loads all accounts and all lines in two queries and aggregates in memory. Appropriate for an infrequently-accessed admin endpoint.
- **AccountSeeder** runs in `@Profile("dev")` (the default profile) via `ApplicationRunner`, after JPA creates the schema. It is idempotent and safe to restart.
