# Reconciliation Layers

## Purpose

Reconciliation ensures that what the system *thinks* happened financially matches what *actually* happened across all layers — from internal invoices all the way to the bank statement. Mismatches indicate data integrity issues, gateway errors, or bugs.

---

## Four-Layer Architecture

```
Layer 1: Invoices ↔ Payments
         (did every invoice get paid? did every payment have an invoice?)

Layer 2: Payments ↔ Ledger
         (does every captured payment have a matching ledger entry?)

Layer 3: Ledger ↔ Settlement Batches
         (do settlement ledger entries match the settlement batch amounts?)

Layer 4: Settlement Batches ↔ External Statements
         (do our batches match the bank's statement?)
```

Each layer builds on the previous. A discrepancy in Layer 1 may cascade to Layer 2 and 3. Fix from the bottom up (Layer 4 first if bank statement is authoritative) or top down if the invoicing system is authoritative.

---

## Layer 1: Invoices ↔ Payments

**What it checks:** For a given date, every invoice that is due should have a corresponding captured payment, and every captured payment should correspond to an invoice.

**Query pattern:**
```sql
-- Find invoices with no matching payment
SELECT i.id, i.invoice_number, i.grand_total, i.due_date
FROM invoices i
LEFT JOIN payment_intents_v2 p ON p.invoice_id = i.id AND p.status = 'SUCCEEDED'
WHERE i.due_date = :targetDate
  AND i.status IN ('PENDING', 'PAST_DUE')
  AND p.id IS NULL
  AND i.merchant_id = :merchantId;

-- Find payments with no matching invoice
SELECT p.id, p.captured_amount, p.created_at
FROM payment_intents_v2 p
LEFT JOIN invoices i ON i.id = p.invoice_id
WHERE p.status = 'SUCCEEDED'
  AND DATE(p.updated_at) = :targetDate
  AND i.id IS NULL
  AND p.merchant_id = :merchantId;
```

**Mismatch types:**

| Code | Meaning | Likely Cause |
|---|---|---|
| `INVOICE_NO_PAYMENT` | Invoice due, no captured payment | Payment failed; dunning should retry |
| `PAYMENT_NO_INVOICE` | Payment succeeded, invoice not found | Bug: payment linked to deleted/missing invoice |
| `AMOUNT_MISMATCH` | Payment amount ≠ invoice grand_total | Partial payment; partial capture by gateway |
| `DUPLICATE_GATEWAY_TXN` | Same gateway_txn_id on multiple payments | Gateway bug; idempotency failure |

---

## Layer 2: Payments ↔ Ledger

**What it checks:** Every `SUCCEEDED` payment intent should have a corresponding `PAYMENT_CAPTURED` ledger entry with the same amount.

**Query pattern:**
```sql
-- Sum of captured payments
SELECT SUM(captured_amount) AS payment_total
FROM payment_intents_v2
WHERE status = 'SUCCEEDED'
  AND DATE(updated_at) = :targetDate
  AND merchant_id = :merchantId;

-- Sum from ledger
SELECT SUM(ll.amount) AS ledger_total
FROM ledger_lines ll
JOIN ledger_entries le ON ll.entry_id = le.id
JOIN ledger_accounts la ON ll.account_id = la.id
WHERE la.code = 'CASH'
  AND ll.side = 'DEBIT'
  AND le.entry_type = 'PAYMENT_CAPTURED'
  AND DATE(le.created_at) = :targetDate
  AND le.merchant_id = :merchantId;
```

**Tolerance:** ±₹0.01 (half-paise rounding from recurring decimal amounts).

**Mismatch signal:** `abs(payment_total - ledger_total) > 0.01`

**Mismatch type:** `PAYMENT_LEDGER_MISMATCH` — indicates a payment was captured without a ledger entry being posted, or a ledger entry was posted for a non-existent payment.

---

## Layer 3: Ledger ↔ Settlement Batches

**What it checks:** Settlement ledger entries match the amounts in the settlement batch table.

**Settlement process:**
1. Nightly at `02:00 UTC`, `SettlementServiceImpl` aggregates all captured payments for the day by gateway
2. Creates a `SettlementBatch` row for each gateway with `gross_amount = sum(captured_amount)` for that gateway
3. Posts ledger entry: `DR CASH / CR SETTLEMENT` for the batch amount

**Query pattern:**
```sql
-- Ledger settlement entries
SELECT SUM(ll.amount) AS ledger_settlement
FROM ledger_lines ll
JOIN ledger_entries le ON ll.entry_id = le.id
WHERE le.entry_type = 'SETTLEMENT'
  AND DATE(le.created_at) = :targetDate
  AND le.merchant_id = :merchantId;

-- Settlement batch totals
SELECT SUM(gross_amount) AS batch_total
FROM settlement_batches
WHERE batch_date = :targetDate
  AND merchant_id = :merchantId;
```

**Mismatch type:** `SETTLEMENT_LEDGER_MISMATCH`

---

## Layer 4: Settlement Batches ↔ External Statements

**What it checks:** The settlement batch amounts match the `external_statement_lines` uploaded from bank files.

```sql
SELECT sb.gateway, sb.gross_amount AS expected, esl.amount AS actual,
       (sb.gross_amount - esl.amount) AS discrepancy
FROM settlement_batches sb
LEFT JOIN external_statement_lines esl ON esl.reference_id = sb.id
WHERE sb.batch_date = :targetDate
  AND sb.merchant_id = :merchantId;
```

**Mismatch type:** `BANK_STATEMENT_MISMATCH`

**When this matters:** This layer is only meaningful when external statement lines are uploaded. If no bank statements are imported, Layer 4 is skipped.

---

## Reconciliation Infrastructure

### Entities

| Table | Purpose |
|---|---|
| `recon_batches` | One row per recon run per date: `status=RUNNING/COMPLETED/FAILED` |
| `recon_mismatches` | One row per detected mismatch: `layer`, `mismatch_type`, `reference_id`, `delta`, `status=OPEN/RESOLVED/IGNORED` |
| `settlement_batches` | Aggregated daily settlement per gateway |
| `external_statement_lines` | Bank-provided transaction lines (uploaded) |

### Mismatch Lifecycle

```
OPEN → RESOLVED (operator manually resolves after investigation)
     → IGNORED  (known false positive; documented)
```

Mismatches cannot be deleted — only resolved or ignored. This provides a full audit trail.

---

## Running Reconciliation

### Automatic (nightly)

```
Cron: 0 10 2 * * * (02:10 UTC)
Job: AdvancedReconciliationScheduler
Guard: JobLock("reconciliation_daily")
```

### On-Demand (admin)

```
POST /api/v1/admin/recon/settle?date=YYYY-MM-DD
GET  /api/v1/admin/recon/daily?date=YYYY-MM-DD  → JSON report
GET  /api/v1/admin/recon/daily.csv?date=YYYY-MM-DD  → CSV download
```

---

## Idempotency of Recon Runs

Running reconciliation for the same date twice:
- Does **not** create duplicate `ReconBatch` rows — the batch for the date is reused or a new one is created with the same date reference
- Does **not** duplicate `ReconMismatch` rows — existing OPEN mismatches are left unchanged; new ones are inserted
- Does **not** affect RESOLVED or IGNORED mismatches

This means a re-run after investigating and resolving mismatches will not undo the resolutions.

---

## Tolerances and False Positives

| Layer | Tolerance | Reason |
|---|---|---|
| Layer 1 | No tolerance (exact match on counts) | Every invoice/payment must be accounted for |
| Layer 2 | ±₹0.01 | Half-paise rounding in daily revenue amortization |
| Layer 3 | ±₹0.01 | Same rounding |
| Layer 4 | ±₹1.00 | Bank statements may round differently per gateway |

Mismatches within tolerance are not inserted as `ReconMismatch` rows.

---

## V1 Basic Reconciliation

Before `AdvancedReconciliationServiceImpl` (V2), a simpler `ReconciliationServiceImpl` (V1) exists:

- Compares: `sum(SUCCEEDED payments for date)` vs `sum(SETTLEMENT ledger entries for date)`
- If `|diff| > 0` → creates a `ReconMismatch(OPEN)`
- Much simpler; used for daily sanity checks
- V2 is used for full 4-layer analysis

Both V1 and V2 can run independently. V1 is faster; V2 is more thorough.

---

## Reconciliation as a Financial Control

This 4-layer reconciliation is a **financial control** — it detects:
- Missing ledger entries (engineering bug)
- Double-posted ledger entries (concurrency bug)
- Gateway reporting errors
- Settlement shortfalls from payment processors

Every open mismatch that is not an expected timing gap should be investigated and resolved. Unresolved mismatches from more than 7 days ago are a compliance concern.
