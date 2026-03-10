# 12 — Billing Guardrails: Credit Carry-Forward, Invoice Rebuild, and Billing Period Guards

## Why This Exists

A billing system accrues implicit trust with every successful charge. That trust is broken
in two ways: billing double, or billing wrong amounts. Phase 17 introduces three orthogonal
guards to prevent both.

---

## 1. Invoice Total Correctness Invariant

### Rule

```
grandTotal = max(subtotal − discountTotal − creditTotal + taxTotal, 0)
```

An invoice's stored `grand_total` **must** equal the derivation from its constituent invoice
lines at all times. Any drift between these two values is a billing bug.

### Enforcement

`InvoiceInvariantService.assertTotalMatchesLines(invoice)` re-derives the expected total from
the `invoice_lines` table and throws `INVOICE_TOTAL_MISMATCH (422)` if they diverge.

This check can be wired into any mutation path (apply-discount, apply-credit, plan change) to
prevent silent corruption from propagating.

### Why Rebuild Exists as an Ops Action

In practice, bugs happen — migration patches, manual line corrections, or partial rollback scripts
can leave `grand_total` out of sync with lines. Rather than silently tolerating the inconsistency
until it affects a customer charge, the `POST /api/v2/invoices/{id}/rebuild-totals` endpoint lets
an operator force-recalculate the totals from lines and permanently record who triggered it and when.

The `rebuilt_at` / `rebuilt_by` columns on the `invoices` table provide an immutable audit trail.
Only DRAFT or OPEN invoices can be rebuilt; terminal states (PAID, VOID, UNCOLLECTIBLE) are frozen.

---

## 2. Credit Carry-Forward

### Why Credits Must Carry Forward

When a user's credit wallet exceeds their invoice amount, the excess cannot be silently discarded —
that would be a financial loss to the customer. Instead, the overflow is preserved as a brand-new
**carry-forward credit note** that remains available for future invoices.

### Rule

```
if totalAvailableCredit > invoiceGrandTotal:
    apply invoiceGrandTotal worth of credit
    create carry-forward credit note for (totalAvailableCredit - invoiceGrandTotal)
```

### Implementation

`CreditCarryForwardService` manages the full credit lifecycle:

| Method | Purpose |
|---|---|
| `createCreditNote(...)` | Creates a new credit note with optional expiry and source invoice linkage |
| `applyCreditsToInvoice(...)` | FIFO application of available (non-expired) credit notes; returns remaining balance |
| `createCarryForwardIfOverflow(...)` | Creates overflow credit note if excess credit exists |
| `getCreditsForUser(userId)` | Lists all credit notes newest-first for the customer credits API |

### Credit Note Expiry

Credit notes may carry an `expires_at` timestamp. Expired notes are filtered out during
application. Carry-forward notes created by the system do **not** have an expiry unless
explicitly set.

### Over-Application Guard

`InvoiceInvariantService.assertCreditWithinBalance(creditToApply, availableBalance, creditNoteId)`
throws `CREDIT_EXCEEDS_BALANCE (422)` if an application would exceed the note's available balance.

---

## 3. Billing Period Guard

### Rule

A new invoice for a subscription **cannot** be created if an OPEN or PAID invoice already covers
an overlapping billing period for that same subscription.

Two billing periods overlap when:
```
periodStart1 < periodEnd2  AND  periodEnd1 > periodStart2
```

### Enforcement

`InvoicePeriodGuard.assertNoPeriodOverlap(subscriptionId, periodStart, periodEnd)` queries
`InvoiceRepository.findOverlappingActiveInvoices(...)` and throws `OVERLAPPING_INVOICE_PERIOD (409)`
if any match is found.

The guard accepts an optional `excludeInvoiceId` parameter for use during invoice rebuilds
where the current invoice must not block itself.

### Why This Matters

Billing-period overlap bugs are most commonly caused by:
- Retry storms (webhook retried after a timeout, creating a second invoice)
- Plan-change proration logic running twice
- Clock-skew in period-boundary calculations

The guard acts as a last-line-of-defence that makes double-billing impossible regardless
of upstream bugs.

---

## 4. Paid Invoice Void Guard

### Rule

A PAID invoice **cannot** be voided without an explicit refund or reversal path.

Voiding a paid invoice without a refund acknowledgment would silently reverse a charge
that has already been collected, creating a financial reconciliation gap.

### Enforcement

`InvoiceInvariantService.assertVoidAllowed(invoice, hasRefundPath)` throws
`PAID_INVOICE_VOID_BLOCKED (409)` when `invoice.status == PAID` and `hasRefundPath == false`.

Callers must explicitly confirm they have a corresponding refund record before calling void.

---

## 5. New DB Columns (V63)

### invoices

| Column | Type | Purpose |
|---|---|---|
| `effective_credit_applied_minor` | `BIGINT NOT NULL DEFAULT 0` | Effective credit applied in paise, auditable separately from CREDIT_APPLIED lines |
| `source_invoice_id` | `BIGINT NULL` | Links rebuilt invoices back to their original |
| `rebuilt_at` | `TIMESTAMP NULL` | When the last rebuild-totals action ran |
| `rebuilt_by` | `VARCHAR(255) NULL` | Principal who triggered the last rebuild |

### credit_notes

| Column | Type | Purpose |
|---|---|---|
| `customer_id` | `BIGINT NULL` | Merchant-facing customer id for cross-merchant queries |
| `available_amount_minor` | `BIGINT NOT NULL DEFAULT 0` | Fast balance check in paise |
| `source_invoice_id` | `BIGINT NULL` | Invoice that triggered creation (for carry-forward audit) |
| `expires_at` | `TIMESTAMP NULL` | Optional expiry; NULL = never expires |

---

## 6. API Reference

### POST /api/v2/invoices/{id}/rebuild-totals

Recalculates all total fields from invoice lines and stamps audit metadata.

```
POST /api/v2/invoices/42/rebuild-totals
→ 200 InvoiceDTO  (with updated grandTotal, rebuiltAt, rebuiltBy)
→ 409 INVOICE_TERMINAL_STATE  (if PAID/VOID/UNCOLLECTIBLE)
→ 404 INVOICE_NOT_FOUND
```

### GET /api/v2/customers/{customerId}/credits

Returns all credit notes for a customer, newest-first.

```
GET /api/v2/customers/10/credits
→ 200 List<CreditNoteDTO>
```

### POST /api/v2/invoices/{id}/apply-credit

Applies available credit wallet balance to an OPEN invoice. Overflow becomes a carry-forward note.

```
POST /api/v2/invoices/42/apply-credit
→ 200 InvoiceDTO  (with updated creditTotal and grandTotal)
→ 409 INVOICE_NOT_OPEN
→ 404 INVOICE_NOT_FOUND
```

---

## 7. Component Map

```
billing/
  guard/
    InvoiceInvariantService   — total correctness + void guard + credit-cap guard
    InvoicePeriodGuard        — no overlapping active invoices for same period
  credit/
    CreditCarryForwardService — FIFO credit application + carry-forward creation
  rebuild/
    InvoiceRebuildService     — ops action: recalculate totals + stamp audit fields
  controller/
    InvoiceBillingGuardsController — POST rebuild-totals, GET credits, POST apply-credit
```
