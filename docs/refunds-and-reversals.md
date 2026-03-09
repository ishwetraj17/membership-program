# Refunds & Reversals Runbook

**Module:** `com.firstclub.payments.refund`  
**Phase:** 11  
**API Version:** V2 (merchant-scoped)

---

## Overview

Phase 11 introduces a complete **partial refund** system with cumulative over-refund protection, double-entry accounting reversals, and tenant-scoped APIs.

Key guarantees:
- **No over-refund** — protected by a pessimistic write lock on the `payments` row
- **Idempotent** — re-sending the same `Idempotency-Key` returns the cached response within 24 hours
- **Atomic** — refund row, payment amount updates, and ledger entry are all within one transaction
- **Immutable audit** — a `domain_event_log` entry is appended on every refund

---

## Database Schema

### `payments` table (V25 additions)

| Column | Type | Description |
|---|---|---|
| `merchant_id` | `BIGINT NULL` | FK to `merchant_accounts.id`. Set for all new V2 captures. Legacy V1 payments have NULL. |
| `captured_amount` | `DECIMAL(18,4)` | Gross amount captured at the gateway. Immutable after capture. |
| `refunded_amount` | `DECIMAL(18,4)` | Cumulative sum of all COMPLETED refunds. Incremented per refund. |
| `disputed_amount` | `DECIMAL(18,4)` | Amount under active chargeback/dispute. Reserved — not refundable. |
| `net_amount` | `DECIMAL(18,4)` | Derived: `captured - refunded - disputed`. Recalculated on every mutation. |

### `refunds_v2` table

| Column | Type | Description |
|---|---|---|
| `id` | `BIGSERIAL PK` | Auto-generated identifier |
| `merchant_id` | `BIGINT NOT NULL` | FK to `merchant_accounts.id` |
| `payment_id` | `BIGINT NOT NULL` | FK to `payments.id` |
| `invoice_id` | `BIGINT NULL` | Optional FK to `invoices.id` |
| `amount` | `DECIMAL(18,4)` | Refund amount |
| `reason_code` | `VARCHAR(64)` | Structured code: `CUSTOMER_REQUEST`, `DUPLICATE_CHARGE`, etc. |
| `status` | `VARCHAR(16)` | `PENDING` → `COMPLETED` or `FAILED` |
| `refund_reference` | `VARCHAR(128) NULL` | Gateway / internal correlation reference |
| `created_at` | `TIMESTAMP` | Auto-set |
| `completed_at` | `TIMESTAMP NULL` | Null until completion |

> **Note:** The legacy `refunds` table (V8) is preserved. `refunds_v2` is a separate table with enhanced tracking.

---

## Payment Status State Machine

```
CAPTURED ─────────────────────────────────────────────────────► REFUNDED
    │                                                               ▲
    │ (partial refund issued)                                       │
    ▼                                                               │
PARTIALLY_REFUNDED ─────────────────────── (full amount refunded) ─┘
    │
    │ (chargeback raised — outside Phase 11 scope)
    ▼
DISPUTED
```

| Status | Refundable? |
|---|---|
| `CAPTURED` | ✅ Yes |
| `PARTIALLY_REFUNDED` | ✅ Yes (up to remaining balance) |
| `REFUNDED` | ❌ No — all funds returned |
| `DISPUTED` | ❌ No — locked pending dispute resolution |
| `FAILED` | ❌ No — capture never happened |

---

## Over-Refund Protection

### Problem (Legacy `RefundService`)
```java
// BUG: Only checks raw payment.amount, not cumulative refunds
if (request.getAmount().compareTo(payment.getAmount()) > 0) {
    throw ...
}
```
This allows `refund(300) + refund(300) = 600` for a `500` payment.

### Fix (`RefundServiceV2Impl`)
1. **Pessimistic write lock** on the Payment row: `findByIdForUpdate()` uses `@Lock(PESSIMISTIC_WRITE)` — concurrent refund requests queue up and the second one sees the updated `refundedAmount`.
2. **Cumulative check**:
   ```
   refundable = capturedAmount - refundedAmount - disputedAmount
   if (request.amount > refundable) → OVER_REFUND (422)
   ```

---

## Reversal Accounting

### MVP Policy: Single-Account Reversal

**DR SUBSCRIPTION_LIABILITY / CR PG_CLEARING** (full refund amount, always)

```
When payment is captured:          When refund is issued:
  DR PG_CLEARING                     DR SUBSCRIPTION_LIABILITY
  CR SUBSCRIPTION_LIABILITY          CR PG_CLEARING
```

**Why this works** for unearned revenue (service not yet delivered): the refunded amount is still fully in `SUBSCRIPTION_LIABILITY` (deferred revenue), so the reversal is correct.

### Known Limitation (documented for future fix)

When part of the subscription period has elapsed and revenue recognition schedules have been posted:
```
Revenue recognition:
  DR SUBSCRIPTION_LIABILITY
  CR REVENUE_SUBSCRIPTIONS
```
After recognition, `SUBSCRIPTION_LIABILITY` has been reduced. A refund reversal of `DR SUBSCRIPTION_LIABILITY` will temporarily **drive the account negative** for the earned portion.

**Future enhancement:** Split the reversal proportionally:
```
earned_ratio = sum(POSTED recognition schedules) / capturedAmount
earned  = refundAmount × earned_ratio
unearned = refundAmount × (1 - earned_ratio)

DR REVENUE_SUBSCRIPTIONS   earned    (reverse recognized revenue)
DR SUBSCRIPTION_LIABILITY  unearned  (reverse deferred revenue)
CR PG_CLEARING             refundAmount
```
Until this is implemented, a monthly reconciliation sweep should flag `SUBSCRIPTION_LIABILITY` balances below zero for review.

---

## API Reference

### Base URL
```
/api/v2/merchants/{merchantId}/payments/{paymentId}/refunds
```

### Create Refund
```http
POST /api/v2/merchants/{merchantId}/payments/{paymentId}/refunds
Idempotency-Key: <client-generated UUID>
Authorization: Bearer <token>
Content-Type: application/json

{
  "amount": 300.00,
  "reasonCode": "CUSTOMER_REQUEST",
  "invoiceId": null,
  "refundReference": "gateway-ref-xyz"
}
```

**Response 201:**
```json
{
  "id": 42,
  "merchantId": 10,
  "paymentId": 100,
  "amount": 300.00,
  "reasonCode": "CUSTOMER_REQUEST",
  "status": "COMPLETED",
  "refundableAmountAfter": 700.00,
  "paymentStatusAfter": "PARTIALLY_REFUNDED",
  "createdAt": "2025-01-01T10:00:00",
  "completedAt": "2025-01-01T10:00:00"
}
```

**Error codes:**

| HTTP | Code | Meaning |
|---|---|---|
| 404 | `PAYMENT_NOT_FOUND` | Payment `paymentId` does not exist |
| 403 | `PAYMENT_MERCHANT_MISMATCH` | Payment belongs to a different merchant |
| 422 | `PAYMENT_MERCHANT_UNRESOLVED` | Legacy payment with no `merchant_id` — not refundable via V2 API |
| 422 | `PAYMENT_NOT_REFUNDABLE` | Payment status is not `CAPTURED` or `PARTIALLY_REFUNDED` |
| 422 | `OVER_REFUND` | `amount > capturedAmount - refundedAmount - disputedAmount` |

### List Refunds
```http
GET /api/v2/merchants/{merchantId}/payments/{paymentId}/refunds
```

### Get Refund by ID
```http
GET /api/v2/merchants/{merchantId}/payments/{paymentId}/refunds/{refundId}
```

### Get Refundable Amount (informational)
```http
GET /api/v2/merchants/{merchantId}/payments/{paymentId}/refunds/refundable-amount
```
Returns a single `BigDecimal`. Not authoritative (use this only for UI display, not as the final refund authority check).

---

## Request Headers

| Header | Required | Description |
|---|---|---|
| `Authorization` | ✅ Always | `Bearer <JWT>` |
| `Idempotency-Key` | ✅ Required for `POST /refunds` | Client-generated UUID for deduplication |
| `Content-Type` | ✅ For `POST` | `application/json` |

---

## Idempotency

The `POST /refunds` endpoint is decorated with `@Idempotent(ttlHours=24)`.

- If the same `Idempotency-Key` is sent within 24 hours, the **cached 201 response is returned** without re-processing.
- Use a fresh UUID per unique refund intent.
- The idempotency key is stored in the `idempotency_keys` table (V5 migration).

---

## Migration Notes (V25)

`V25__refunds_v2_and_payment_amount_tracking.sql` performs:
1. `ALTER TABLE payments` — adds `captured_amount`, `refunded_amount`, `disputed_amount`, `net_amount`, `merchant_id` (all nullable-safe with defaults)
2. `UPDATE payments` — backfills `captured_amount = amount` and `net_amount = amount` for all existing `CAPTURED` rows
3. Drops and recreates the `ck_ledger_ref_type` CHECK constraint to include `REVENUE_RECOGNITION_SCHEDULE` (added in Phase 10 Java enum but missing from DB constraint)
4. Creates `refunds_v2` table with indexes

**Rollback:** The `ALTER TABLE ... ADD COLUMN` operations are non-destructive. To roll back: drop the new columns from `payments`, drop `refunds_v2`, and restore the original CHECK constraint.

---

## Transaction Flow

```
POST /refunds
  │
  ├─ @Idempotent filter checks idempotency_keys (before TX)
  │
  └─ @Transactional RefundServiceV2Impl.createRefund()
       │
       ├─ 1. findByIdForUpdate(paymentId) ─── PESSIMISTIC WRITE LOCK
       ├─ 2. validateMerchantOwnership()
       ├─ 3. validate payment.status ∈ {CAPTURED, PARTIALLY_REFUNDED}
       ├─ 4. compute refundable = capturedAmount - refundedAmount - disputedAmount
       ├─ 5. validate request.amount ≤ refundable
       ├─ 6. save RefundV2{status=PENDING}
       ├─ 7. ledgerService.postEntry(REFUND_ISSUED, REFUND, refundId, ...)
       │       └─ DR SUBSCRIPTION_LIABILITY / CR PG_CLEARING
       ├─ 8. payment.refundedAmount += amount
       ├─ 9. payment.netAmount = capturedAmount - refundedAmount - disputedAmount  
       ├─ 10. payment.status = REFUNDED | PARTIALLY_REFUNDED
       ├─ 11. paymentRepository.save(payment)
       ├─ 12. refund.status = COMPLETED, refund.completedAt = now()
       ├─ 13. refundV2Repository.save(refund)
       ├─ 14. domainEventLog.record("REFUND_V2_ISSUED", ...)
       └─ 15. outboxService.publish(REFUND_ISSUED, ...)
             └─ All committed atomically; any exception rolls back all 15 steps
```
