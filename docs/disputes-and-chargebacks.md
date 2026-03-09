# Disputes and Chargebacks

Fintech-grade dispute handling implemented in Phase 12.  
A dispute is a formal challenge by the card network or issuing bank against a captured payment.  
A chargeback is the terminal form of a lost dispute: the money leaves the merchant permanently.

---

## 1. Entities

### `disputes`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | |
| `merchant_id` | BIGINT FK → `merchant_accounts` | Tenant isolation |
| `payment_id` | BIGINT FK → `payments` | One active dispute per payment at a time |
| `customer_id` | BIGINT FK → `customers` | Party who raised the dispute |
| `amount` | DECIMAL(18,4) | Amount under dispute |
| `reason_code` | VARCHAR(64) | e.g. `FRAUDULENT_CHARGE`, `ITEM_NOT_RECEIVED` |
| `status` | VARCHAR(32) | See lifecycle below |
| `opened_at` | TIMESTAMPTZ | Set by DB on INSERT |
| `due_by` | TIMESTAMPTZ | Optional evidence deadline |
| `resolved_at` | TIMESTAMPTZ | Null until resolved |

### `dispute_evidence`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | |
| `dispute_id` | BIGINT FK → `disputes` | |
| `evidence_type` | VARCHAR(64) | e.g. `INVOICE`, `PHOTO`, `TRACKING_INFO` |
| `content_reference` | TEXT | S3 URI, URL, or document reference |
| `uploaded_by` | BIGINT FK → `users` | Who uploaded |
| `created_at` | TIMESTAMPTZ | Immutable after creation |

---

## 2. Dispute Lifecycle

```
 ┌──────────────────────────────────────────────────────────────┐
 │                        OPEN                                  │
 │  • Dispute created                                           │
 │  • Payment frozen (status → DISPUTED)                        │
 │  • payment.disputedAmount += dispute.amount                  │
 │  • DR DISPUTE_RESERVE / CR PG_CLEARING (accounting)         │
 └──────────────────────┬───────────────────────────────────────┘
                        │ POST /{id}/review
                        ▼
 ┌──────────────────────────────────────────────────────────────┐
 │                   UNDER_REVIEW                               │
 │  • Internal review in progress                               │
 │  • Evidence can still be uploaded (if before due_by)         │
 └──────────────────────┬───────────────────────────────────────┘
                        │ POST /{id}/resolve
               ┌────────┴────────┐
               │ outcome=WON     │ outcome=LOST
               ▼                 ▼
  ┌────────────────┐  ┌────────────────────────────────┐
  │      WON       │  │             LOST               │
  │  Reserve back  │  │  Permanent loss (chargeback)   │
  │  to merchant   │  │  capturedAmount reduced         │
  └────────────────┘  └────────────────────────────────┘
```

**Valid transitions:**

| From | To | Trigger |
|---|---|---|
| `OPEN` | `UNDER_REVIEW` | `POST /{disputeId}/review` |
| `OPEN` | `WON` or `LOST` | `POST /{disputeId}/resolve` |
| `UNDER_REVIEW` | `WON` or `LOST` | `POST /{disputeId}/resolve` |
| `WON` / `LOST` | — | Terminal, no further transitions |

---

## 3. Payment State Interaction

When a dispute is **opened**:
- `payment.status` → `DISPUTED`
- `payment.disputedAmount += dispute.amount`
- `payment.netAmount = capturedAmount − refundedAmount − disputedAmount`
- The `disputedAmount` is excluded from the refundable formula in `RefundServiceV2`, preventing over-refunding disputed funds.

When a dispute is **won** (merchant wins):
- `payment.disputedAmount -= dispute.amount` (reserve released)
- `payment.status` → `CAPTURED` (or `PARTIALLY_REFUNDED` if prior refunds exist)
- `payment.netAmount` restored to `capturedAmount − refundedAmount`

When a dispute is **lost** (chargeback posted):
- `payment.capturedAmount -= dispute.amount` (permanent loss)
- `payment.disputedAmount -= dispute.amount` (reserve cleared)
- `payment.status` stays `DISPUTED` — terminal state for a lost chargeback
- `payment.netAmount` reflects the permanently reduced captured amount

---

## 4. Accounting Policy

All entries use double-entry bookkeeping (every entry balances to zero).

### 4.1 Dispute Opened

Funds are frozen in a reserve account to flag disputed money.

```
DR  DISPUTE_RESERVE   (ASSET)   +{amount}    ← hold the disputed funds
CR  PG_CLEARING       (ASSET)   −{amount}    ← remove from the settlement pool
```

Ledger entry type: `DISPUTE_OPENED`

### 4.2 Dispute Won

The card network ruled in the merchant's favour — money returns from reserve to clearing.

```
DR  PG_CLEARING       (ASSET)   +{amount}    ← back to settlement pool
CR  DISPUTE_RESERVE   (ASSET)   −{amount}    ← release the hold
```

Ledger entry type: `DISPUTE_WON`

### 4.3 Dispute Lost (Chargeback)

The card network ruled against the merchant — the money is permanently gone.

```
DR  CHARGEBACK_EXPENSE  (EXPENSE)  +{amount}   ← record the loss on the P&L
CR  DISPUTE_RESERVE     (ASSET)    −{amount}   ← extinguish the reserve
```

Ledger entry type: `CHARGEBACK_POSTED`

> **Note:** CHARGEBACK_EXPENSE is an EXPENSE account. Its debit grow the expense balance which flows through to reduce net income on the income statement — exactly what a real chargeback does.

---

## 5. Evidence Handling

Evidence can be uploaded at any time while the dispute has a `dueBy` timestamp in the future, or at any time if `dueBy` is null.

```
POST /api/v2/merchants/{merchantId}/disputes/{disputeId}/evidence
{
  "evidenceType": "INVOICE",
  "contentReference": "s3://evidence-bucket/inv_123.pdf",
  "uploadedBy": 42
}
```

Evidence is **immutable after creation** (no update/delete endpoints). Evidence items are returned ordered by `createdAt ASC`.

---

## 6. One Active Dispute Per Payment Rule

A payment can have at most one active (OPEN or UNDER_REVIEW) dispute at a time. Attempting to open a second one returns:

```
HTTP 409 Conflict
{ "errorCode": "ACTIVE_DISPUTE_EXISTS" }
```

A WON or LOST dispute is terminal, so a new dispute can be opened on the same payment after a previous one is resolved.

---

## 7. API Reference

### Open a Dispute

```
POST /api/v2/merchants/{merchantId}/payments/{paymentId}/disputes
Idempotency-Key: <uuid>           ← required, TTL 24 h
```

Request:
```json
{
  "customerId": 5,
  "amount": "300.00",
  "reasonCode": "FRAUDULENT_CHARGE",
  "dueBy": "2025-04-01T00:00:00"   // optional evidence deadline
}
```

Response `201 Created`:
```json
{
  "id": 10,
  "merchantId": 1,
  "paymentId": 42,
  "customerId": 5,
  "amount": "300.00",
  "reasonCode": "FRAUDULENT_CHARGE",
  "status": "OPEN",
  "openedAt": "2025-03-01T12:00:00",
  "paymentStatusAfter": "DISPUTED"
}
```

### List Disputes for a Payment

```
GET /api/v2/merchants/{merchantId}/payments/{paymentId}/disputes
```

### List All Disputes for a Merchant

```
GET /api/v2/merchants/{merchantId}/disputes[?status=OPEN]
```

### Get One Dispute

```
GET /api/v2/merchants/{merchantId}/disputes/{disputeId}
```

### Move to Under Review

```
POST /api/v2/merchants/{merchantId}/disputes/{disputeId}/review
```

### Resolve a Dispute

```
POST /api/v2/merchants/{merchantId}/disputes/{disputeId}/resolve
```

```json
{
  "outcome": "WON",
  "resolutionNotes": "Customer provided insufficient evidence."
}
```

Valid `outcome` values: `WON`, `LOST`.

### Add Evidence

```
POST /api/v2/merchants/{merchantId}/disputes/{disputeId}/evidence
```

```json
{
  "evidenceType": "INVOICE",
  "contentReference": "s3://evidence-bucket/invoice.pdf",
  "uploadedBy": 42
}
```

Returns `201 Created`. Returns `422 EVIDENCE_DEADLINE_PASSED` if `now > dispute.dueBy`.

### List Evidence

```
GET /api/v2/merchants/{merchantId}/disputes/{disputeId}/evidence
```

---

## 8. Error Codes

| Code | HTTP | When |
|---|---|---|
| `PAYMENT_NOT_FOUND` | 404 | Payment does not exist |
| `PAYMENT_MERCHANT_UNRESOLVED` | 422 | Payment has no merchant association |
| `PAYMENT_MERCHANT_MISMATCH` | 403 | Payment belongs to a different merchant |
| `PAYMENT_NOT_DISPUTABLE` | 422 | Payment status is not CAPTURED or PARTIALLY_REFUNDED |
| `ACTIVE_DISPUTE_EXISTS` | 409 | An OPEN or UNDER_REVIEW dispute already exists for this payment |
| `DISPUTE_AMOUNT_EXCEEDS_LIMIT` | 422 | amount > capturedAmount − refundedAmount − existing disputedAmount |
| `DISPUTE_NOT_FOUND` | 404 | Dispute does not exist or belongs to a different merchant |
| `INVALID_DISPUTE_TRANSITION` | 422 | Status transition not allowed (e.g. LOST → UNDER_REVIEW) |
| `DISPUTE_ALREADY_RESOLVED` | 422 | Dispute is already in a terminal state (WON or LOST) |
| `INVALID_DISPUTE_OUTCOME` | 422 | outcome field is not WON or LOST |
| `EVIDENCE_DEADLINE_PASSED` | 422 | Current time is past dispute.dueBy |

---

## 9. Implementation Notes

- **Pessimistic write lock**: `paymentRepository.findByIdForUpdate()` is used when opening or resolving a dispute to prevent concurrent mutations of the same payment row.
- **Idempotency**: `POST /disputes` is decorated with `@Idempotent(ttlHours=24)`. The same `Idempotency-Key` within 24 h returns the cached response without creating a second dispute.
- **Tenant isolation**: All lookup queries include `merchantId` as a filter (e.g. `findByMerchantIdAndId`). Cross-merchant access returns 404, not 403, to avoid leaking the existence of records.
- **Audit trail**: `DomainEventLog` records `DISPUTE_OPENED`, `DISPUTE_UNDER_REVIEW`, and `DISPUTE_RESOLVED` events.
- **No OutboxService integration**: Dispute events are audit-only at this phase. Webhook delivery for dispute lifecycle can be added as a follow-up by creating an `OutboxEvent` handler.
