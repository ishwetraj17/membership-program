# Revenue Recognition

## Standard Implemented

**ASC 606 / IFRS 15** — Revenue is recognized as performance obligations are satisfied over the subscription period.

For subscription businesses, this means: revenue collected upfront is deferred (a liability) and recognized ratably as each day of service is delivered.

---

## Why Deferred Revenue?

A customer who pays ₹499 for a 30-day Gold plan on January 1:
- On January 1, **we owe them 30 days of service**
- On January 1, we have **not earned** the ₹499 — we have a liability
- On January 2, we have delivered 1 day of service — we have earned ₹16.63
- On January 31, all 30 days delivered — full ₹499 is earned

**Booking the full ₹499 as revenue on January 1 is incorrect** under ASC 606 and IFRS 15. It overstates revenue in Period 1 and understates it thereafter.

---

## Implementation

### Schedule Generation

When a subscription invoice is finalized, `RevenueRecognitionScheduleServiceImpl` generates one `RevenueRecognitionSchedule` row per day in the billing period.

```
Invoice: INV-2025-0001
Amount: ₹499.00
Period: Jan 1 – Jan 30 (30 days)
Daily rate: ₹499.00 / 30 = ₹16.6333...

Generated schedules:
row 1: recognition_date=2025-01-01, amount=₹16.63, status=PENDING
row 2: recognition_date=2025-01-02, amount=₹16.63, status=PENDING
...
row 29: recognition_date=2025-01-29, amount=₹16.63, status=PENDING
row 30: recognition_date=2025-01-30, amount=₹16.64, status=PENDING  ← rounding adjustment
```

**Rounding rule:** Last row absorbs the rounding remainder to ensure `sum(schedule amounts) == invoice.grand_total`.

### Entity: `RevenueRecognitionSchedule`

```
id                UUID
merchant_id       UUID
subscription_id   UUID FK
invoice_id        UUID FK
ledger_entry_id   UUID FK → ledger_entries (NULL until posted)
recognition_date  DATE
amount            NUMERIC(19,4)
currency          VARCHAR(3)
status            ENUM(PENDING, POSTED)
created_at        TIMESTAMPTZ
```

---

## Nightly Posting

**Scheduler:** `RevenueRecognitionPostingServiceImpl`, triggered nightly at `03:00 UTC`.

### Algorithm

```
1. Acquire JobLock("revenue_recognition_daily")
2. Find all schedules WHERE recognition_date <= today AND status = PENDING
3. For each schedule:
   a. Call self.postSingleRecognition(scheduleId) in REQUIRES_NEW transaction
   b. Inside REQUIRES_NEW:
      - Reload schedule (may have been posted by a concurrent run)
      - If status == POSTED → return (idempotency guard)
      - Post ledger entry:
          DR  SUBSCRIPTION_LIABILITY  {amount}
          CR  REVENUE_SUBSCRIPTIONS            {amount}
          reference_type=REVENUE_RECOGNITION_SCHEDULE, reference_id={scheduleId}
      - Set schedule.ledger_entry_id = entry.id
      - Set schedule.status = POSTED
      - COMMIT REQUIRES_NEW
4. Release JobLock
```

### Why `REQUIRES_NEW` Per Row?

Each schedule row is committed independently. If row 15 of 30 fails (e.g., a transient DB error), rows 1–14 remain `POSTED` and row 15 remains `PENDING`. The next nightly run picks up row 15 and succeeds. There is no scenario where a failure on one row rolls back successfully posted rows.

---

## Invariants

| Invariant | Enforced Where |
|---|---|
| `sum(schedules) == invoice.recognizable_amount` | Checked at schedule generation time |
| No duplicate posting per schedule row | Status check in `REQUIRES_NEW` + `ledger_entry_id` UNIQUE on schedule |
| Every posted row references a ledger entry | `ledger_entry_id` set atomically with `status=POSTED` |
| Ledger entry balanced: DR SUBSCRIPTION_LIABILITY == CR REVENUE_SUBSCRIPTIONS | `LedgerServiceImpl.postEntry()` balance validation |

---

## Revenue Recognition Across Periods

| Scenario | Behavior |
|---|---|
| Subscription created mid-month | Schedule starts from start_date; prorated to end of period |
| Subscription renewed | New schedule generated for new billing period (separate invoice) |
| Subscription cancelled | Schedule for future days remains PENDING; should be voided — **gap: void/reverse logic not yet implemented** |
| Upgrade mid-cycle | Credit note issued for remaining days of old plan; new invoice for new plan; new schedule for new plan days |
| Refund issued | Creates reversal entry in ledger; does not automatically reverse POSTED schedules — **gap: no reversal of already-recognized revenue** |

---

## Ledger Flow Summary

```
Subscription Created
    │
    ▼
Invoice Finalized
    ├── DR RECEIVABLE / CR SUBSCRIPTION_LIABILITY  (full amount)
    └── Revenue schedules generated (one per day)

Payment Captured
    └── DR CASH / CR RECEIVABLE  (full amount)

Nightly (each day):
    └── DR SUBSCRIPTION_LIABILITY / CR REVENUE_SUBSCRIPTIONS  (daily slice)

End of Period:
    SUBSCRIPTION_LIABILITY balance = 0 (fully recognized)
    REVENUE_SUBSCRIPTIONS balance += invoice amount
    CASH balance += invoice amount (from payment)
```

---

## Financial Reporting Integration

### Total Deferred Revenue (at any point in time)
```sql
SELECT SUM(amount)
FROM revenue_recognition_schedules
WHERE status = 'PENDING'
  AND merchant_id = :merchantId;
```

### Revenue Recognized in a Period
```sql
SELECT SUM(amount)
FROM revenue_recognition_schedules
WHERE status = 'POSTED'
  AND recognition_date BETWEEN :fromDate AND :toDate
  AND merchant_id = :merchantId;
```

### Revenue Recognized via Ledger (cross-check)
```sql
SELECT SUM(ll.amount)
FROM ledger_lines ll
JOIN ledger_entries le ON ll.entry_id = le.id
JOIN ledger_accounts la ON ll.account_id = la.id
WHERE la.code = 'REVENUE_SUBSCRIPTIONS'
  AND ll.side = 'CREDIT'
  AND le.created_at BETWEEN :fromDate AND :toDate
  AND le.merchant_id = :merchantId;
```

These two queries must produce equal results. If they diverge, a schedule was posted without a balancing ledger entry (invariant violation — investigate immediately).

---

## Known Gaps

| Gap | Risk |
|---|---|
| No reversal of POSTED schedules on refund | Revenue remains recognized even after refund — overstates earned revenue |
| No void of PENDING schedules on cancellation | Deferred revenue liability remains on books after cancellation |
| No proration for partial-month cancellations | ASC 606 compliance is approximate for mid-period cancellations |
| No GSTR export of recognized revenue | GST compliance requires separate tax reporting (future phases) |

---

## Phase 14 Hardening

Phase 14 made the revenue engine **provably safe** by adding three new columns, a waterfall projection table, and strengthening generation/posting guards.

### Generation Fingerprint

Every generated schedule row now carries a `generation_fingerprint` (SHA-256 hex, 64 chars):

```
fingerprint = SHA-256( invoiceId : subscriptionId : grandTotal : periodStart : periodEnd )
```

All rows from one generation of an invoice share the same fingerprint.

**What it protects:**
- Secondary idempotency guard — after `existsByInvoiceId()`, a second check on `existsByGenerationFingerprint()` closes the narrow TOCTOU window where two concurrent requests could both pass the first check before either commits.
- Audit trail — operators can verify that the invoice parameters at generation time match the current invoice state.

### Posting Run ID

Every schedule row posted by a batch run is stamped with that run's `posting_run_id` (`System.currentTimeMillis()` at the start of `postDueRecognitionsForDate()`).

```sql
-- All rows posted in a specific run
SELECT * FROM revenue_recognition_schedules WHERE posting_run_id = :runId;
```

This enables per-run audit and makes it easy to correlate posting failures to a specific invocation.

### Catch-Up Run

When the repair endpoint is called with `force=true`:

```
POST /api/v2/admin/repair/revenue-recognition/{invoiceId}/regenerate?force=true
```

The `RevenueScheduleRegenerateAction` calls `regenerateScheduleForInvoice()` which:
1. Deletes all `PENDING` schedule rows for the invoice (POSTED rows are never touched)
2. Generates fresh rows with `catch_up_run = true`

Reports can filter by `catch_up_run` to distinguish normal day-of recognition from retroactive catch-up runs.

### Revenue Recognition Ceiling Check

`postSingleRecognitionInRun()` now enforces a ceiling before writing a ledger entry:

```
sum(POSTED for invoice) + this_row.amount ≤ sum(ALL scheduled for invoice)
```

If the ceiling would be breached, a `REVENUE_CEILING_BREACHED` error (HTTP 422) is thrown and the row remains `PENDING`. This prevents even a schedule-generation bug from causing over-recognition.

### Waterfall Projection

A new `revenue_waterfall_projection` table holds one row per `(merchant_id, business_date)`:

| Column | Description |
|---|---|
| `billed_amount` | Invoices finalised (PAID) on this date |
| `deferred_opening` | Running deferred revenue balance at start of day |
| `deferred_closing` | `opening + billed − recognized − refunded − disputed` |
| `recognized_amount` | Schedule rows POSTED on this date |
| `refunded_amount` | Refunds applied on this date (Phase 14: always 0) |
| `disputed_amount` | Disputes opened on this date (Phase 14: always 0) |

Admin APIs:
- `GET /api/v2/admin/revenue/waterfall?from=&to=` — all merchants
- `GET /api/v2/admin/revenue/waterfall/merchant/{id}?from=&to=` — single merchant
- `POST /api/v2/admin/revenue/waterfall/merchant/{id}/refresh?date=` — recompute one row
- `GET /api/v2/admin/revenue/schedules/{invoiceId}` — per-invoice schedule view including fingerprint, run ID, catch-up flag

### Updated Write-Path Summary

| Guard | Before Phase 14 | After Phase 14 |
|---|---|---|
| Duplicate generation | `existsByInvoiceId()` | + `existsByGenerationFingerprint()` (secondary TOCTOU guard) |
| Duplicate posting | `status == POSTED` after `SELECT FOR UPDATE` | Same + ceiling check |
| Catch-up mode | Not supported | `force=true` on repair endpoint; `catch_up_run` column |
| Batch traceability | None | `posting_run_id` on every posted row |
| Waterfall view | None | `revenue_waterfall_projection` table + admin API |

