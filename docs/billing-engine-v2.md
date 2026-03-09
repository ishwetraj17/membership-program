# Billing Engine V2

Phase 8 of the membership platform introduces proper billing artifact management:
sequential merchant-scoped invoice numbering, a full discount system with redemption
tracking, and granular invoice total breakdown columns.

---

## 1. Overview

| Feature | Description |
|---|---|
| Invoice sequences | Per-merchant sequential number generation with pessimistic locking |
| Discounts | FIXED or PERCENTAGE codes scoped to a merchant with validity windows and limits |
| Discount redemptions | One discount per invoice; per-customer limits enforced |
| Invoice total breakdown | `subtotal`, `discount_total`, `credit_total`, `tax_total`, `grand_total` |

---

## 2. Database Schema (V22)

### `invoice_sequences`

Stores the next available sequence number per merchant.  A pessimistic write lock is
acquired before every increment to prevent duplicate numbers under concurrent load.

| Column | Type | Notes |
|---|---|---|
| `merchant_id` | BIGINT PK | FK → merchant_accounts |
| `current_number` | BIGINT | Last generated number (0 = none yet) |
| `prefix` | VARCHAR(32) | e.g. `FCM` |
| `updated_at` | TIMESTAMP | Updated on every increment |

### `discounts`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `merchant_id` | BIGINT NOT NULL | FK → merchant_accounts |
| `code` | VARCHAR(64) | Case-insensitive unique per merchant |
| `discount_type` | VARCHAR(16) | `FIXED` or `PERCENTAGE` |
| `value` | DECIMAL(18,4) | Absolute amount or percentage (0-100) |
| `currency` | VARCHAR(10) | Required for FIXED |
| `max_redemptions` | INT | NULL = unlimited |
| `per_customer_limit` | INT | NULL = unlimited per customer |
| `valid_from` | TIMESTAMP | Start of validity window |
| `valid_to` | TIMESTAMP | End of validity window |
| `status` | VARCHAR(32) | `ACTIVE`, `INACTIVE`, `EXPIRED` |
| `created_at` | TIMESTAMP | |

Unique constraint: `(merchant_id, code)`.

### `discount_redemptions`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `discount_id` | BIGINT NOT NULL | FK → discounts |
| `customer_id` | BIGINT NOT NULL | FK → customers |
| `invoice_id` | BIGINT NOT NULL | FK → invoices |
| `redeemed_at` | TIMESTAMP | |

### `invoices` (altered columns)

| New Column | Type | Notes |
|---|---|---|
| `merchant_id` | BIGINT NULL | NULL for legacy userId-based invoices |
| `invoice_number` | VARCHAR(64) NULL | `{PREFIX}-{NNNNNN}` format |
| `subtotal` | DECIMAL(18,4) | Sum of PLAN_CHARGE + PRORATION lines |
| `discount_total` | DECIMAL(18,4) | Absolute value of DISCOUNT lines |
| `credit_total` | DECIMAL(18,4) | Absolute value of CREDIT_APPLIED lines |
| `tax_total` | DECIMAL(18,4) | Sum of TAX lines |
| `grand_total` | DECIMAL(18,4) | `max(subtotal − discount − credit + tax, 0)` |

---

## 3. Invoice Numbering

```
InvoiceNumberService.generateNextInvoiceNumber(merchantId)
  └─► findByMerchantIdWithLock(merchantId)   ← @Lock(PESSIMISTIC_WRITE)
      ├── if absent → create row with prefix="INV", currentNumber=0
      └── increment currentNumber, save
          └── return "{prefix}-{%06d % currentNumber}"
            e.g. "FCM-000001"
```

**Concurrency safety**: The pessimistic write lock on the sequence row serialises
concurrent invoice number requests for the same merchant.  No Redis or application-level
locking is required.

---

## 4. Discount Lifecycle

```
ACTIVE → (by admin) INACTIVE
ACTIVE → (auto, validTo passed) EXPIRED
```

`createDiscount` validation rules:
- `PERCENTAGE` value must be in `(0, 100]`
- `validFrom` must be strictly before `validTo`
- code must be unique per merchant (case-insensitive)

---

## 5. Applying a Discount to an Invoice

`DiscountService.applyDiscountToInvoice(merchantId, invoiceId, {code, customerId})`

Validation sequence (fail fast):

1. Discount found by `(merchantId, code)` — 404 if not found
2. `discount.status == ACTIVE` — else error
3. `now ∈ [validFrom, validTo]` — else "outside validity window"
4. `maxRedemptions` check (`countByDiscountId < maxRedemptions` if non-null)
5. `perCustomerLimit` check (`countByDiscountIdAndCustomerId < limit` if non-null)
6. Invoice found by `(invoiceId, merchantId)` — 404 if not found
7. Invoice `status == OPEN` — else error
8. `existsByDiscountIdAndInvoiceId` — else "already applied"

Discount amount computation:
- `FIXED`: `min(discount.value, invoice.subtotal)` — capped to prevent negative grand total
- `PERCENTAGE`: `invoice.subtotal × (discount.value / 100)` rounded HALF_UP to 4 d.p.

A `DISCOUNT` invoice line is saved with a **negative** amount, then
`InvoiceTotalService.recomputeTotals(invoice)` is called to refresh all breakdown columns.

---

## 6. Invoice Total Recomputation

`InvoiceTotalService.recomputeTotals(invoice)` iterates all `InvoiceLine` rows and
classifies each by `lineType`:

| lineType | Contributes to |
|---|---|
| `PLAN_CHARGE` | `subtotal` (positive) |
| `PRORATION` | `subtotal` (signed — can be negative for credits) |
| `TAX` | `taxTotal` |
| `DISCOUNT` | `discountTotal` (stored negative, tracked as absolute) |
| `CREDIT_APPLIED` | `creditTotal` (stored negative, tracked as absolute) |

```
grandTotal = max(subtotal − discountTotal − creditTotal + taxTotal, 0)
totalAmount = grandTotal   ← backward compat for legacy code
```

---

## 7. REST API

### Discount Management — `DiscountController`

Base path: `/api/v2/merchants/{merchantId}/discounts`

| Method | Path | Request body | Response | Description |
|---|---|---|---|---|
| POST | `/` | `DiscountCreateRequestDTO` | 201 `DiscountResponseDTO` | Create discount |
| GET | `/` | — | 200 `List<DiscountResponseDTO>` | List all discounts |
| GET | `/{discountId}` | — | 200 `DiscountResponseDTO` | Fetch single discount |

#### `DiscountCreateRequestDTO` fields

| Field | Type | Validation |
|---|---|---|
| `code` | String | `@NotBlank` |
| `discountType` | `DiscountType` | `@NotNull` — `FIXED` or `PERCENTAGE` |
| `value` | BigDecimal | `@NotNull @Positive` |
| `currency` | String | Required for `FIXED` |
| `maxRedemptions` | Integer | Optional; `null` = unlimited |
| `perCustomerLimit` | Integer | Optional; `null` = unlimited |
| `validFrom` | LocalDateTime | `@NotNull` |
| `validTo` | LocalDateTime | `@NotNull`, must be after `validFrom` |

---

### Invoice Discount — `InvoiceDiscountController`

Base path: `/api/v2/merchants/{merchantId}/invoices/{invoiceId}`

| Method | Path | Request body | Response | Description |
|---|---|---|---|---|
| POST | `/apply-discount` | `ApplyDiscountRequestDTO` | 200 `InvoiceSummaryDTO` | Apply discount |
| GET | `/summary` | — | 200 `InvoiceSummaryDTO` | Full invoice breakdown |

#### `ApplyDiscountRequestDTO` fields

| Field | Type | Validation |
|---|---|---|
| `code` | String | `@NotBlank` |
| `customerId` | Long | `@NotNull` |

#### `InvoiceSummaryDTO` fields

`id`, `invoiceNumber`, `merchantId`, `userId`, `subscriptionId`, `status`, `currency`,
`subtotal`, `discountTotal`, `creditTotal`, `taxTotal`, `grandTotal`,
`dueDate`, `periodStart`, `periodEnd`, `createdAt`, `lines[]`

---

## 8. Key Design Decisions

### Backward Compatibility
Existing invoices created by `InvoiceService.createInvoiceForSubscription` have
`merchant_id = NULL` and `invoice_number = NULL`.  The new breakdown columns default
to `0` so no data migration is needed.  `totalAmount` is kept in sync with `grandTotal`
by `InvoiceTotalService` so legacy code reading `totalAmount` continues to work.

### Pessimistic Locking vs. Optimistic
`InvoiceSequenceRepository.findByMerchantIdWithLock` uses `@Lock(PESSIMISTIC_WRITE)`.
This is appropriate because invoice number generation is a high-contention, low-frequency
operation where optimistic retries would produce gaps in the sequence.

### One Discount Per Invoice (No Stacking)
A `discount_redemptions` record is checked via
`existsByDiscountIdAndInvoiceId` before applying.  The current design intentionally
prevents multiple discounts on a single invoice to keep the accounting straightforward.

### FIXED Discount Capped at Subtotal
When a FIXED discount value exceeds the invoice subtotal (e.g., `SAVE2000` on a
`₹500` invoice), the discount is capped at `subtotal` so `grandTotal` floors at 0
without requiring the floor-at-zero guard to do extra work.

---

## 9. Testing

| Class | Type | Coverage |
|---|---|---|
| `InvoiceNumberServiceTest` | Unit | Sequential generation, custom prefix, large counters, initSequence |
| `InvoiceTotalServiceTest` | Unit | All line type combinations, floor-at-zero, empty lines |
| `DiscountServiceTest` | Unit | Create validations, FIXED/PERCENTAGE application, all guard conditions |
| `DiscountControllerTest` | Integration | CRUD endpoints, duplicate code, invalid percentage |
| `InvoiceDiscountControllerTest` | Integration | Apply FIXED/PERCENTAGE, PAID rejection, duplicate rejection, summary endpoint |
