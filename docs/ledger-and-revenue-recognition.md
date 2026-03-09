# Ledger and Revenue Recognition

## Overview

This document describes the double-entry accounting model used by the FirstClub billing
platform and the Revenue Recognition Engine introduced in Phase 10.

---

## 1. Chart of Accounts

The platform uses four core ledger accounts, seeded by `AccountSeeder` (`@Profile("dev")`).
Integration tests must seed these accounts manually (see `RevenueRecognitionControllerTest`).

| Account Name          | Type       | Normal Balance | Purpose                                        |
|-----------------------|------------|----------------|------------------------------------------------|
| `PG_CLEARING`         | ASSET      | DEBIT          | Cash held at the payment gateway               |
| `BANK`                | ASSET      | DEBIT          | Settled funds in our bank account              |
| `SUBSCRIPTION_LIABILITY` | LIABILITY | CREDIT      | Deferred revenue: cash received, service not yet delivered |
| `REVENUE_SUBSCRIPTIONS`  | INCOME    | CREDIT      | Earned revenue after service delivery          |

---

## 2. Ledger Entry Types

| Entry Type         | Trigger                                          |
|--------------------|--------------------------------------------------|
| `PAYMENT_CAPTURED` | Payment gateway webhook confirms capture         |
| `REFUND_ISSUED`    | Refund processed                                 |
| `REVENUE_RECOGNIZED` | Daily recognition run posts a schedule row     |
| `SETTLEMENT`       | Funds settled from PG_CLEARING to BANK           |

---

## 3. Payment Capture Journal Entry

When a subscription payment is captured:

```
DR  PG_CLEARING          +amount   (cash received at gateway)
CR  SUBSCRIPTION_LIABILITY +amount (deferred: service not yet delivered)
```

---

## 4. Revenue Recognition

### 4.1 Concept

Under accrual accounting, revenue is *earned* as the subscribed service is delivered —
one day at a time over the billing period, not at the moment cash is received.

When an invoice is marked `PAID`, the system generates a daily schedule of recognition
rows.  Each row represents a single calendar day's worth of revenue and begins as
`PENDING`.  A daily job (or an admin-triggered run) posts each `PENDING` row to the
ledger:

```
DR  SUBSCRIPTION_LIABILITY  +daily_amount    (deferred revenue consumed)
CR  REVENUE_SUBSCRIPTIONS   +daily_amount    (earned revenue recognised)
```

### 4.2 Schedule Generation

Triggered automatically from `InvoiceService.onPaymentSucceeded` for every PAID invoice
that has a non-null `subscriptionId`, `periodStart`, and `periodEnd`.

**Algorithm:**
```
numDays       = DAYS.between(periodStart.toLocalDate(), periodEnd.toLocalDate())
dailyAmount   = grandTotal / numDays  (scale 4, HALF_UP)
lastDayAmount = grandTotal - dailyAmount * (numDays - 1)   // absorbs rounding residue
```

This guarantees `SUM(schedule.amount) == invoice.grandTotal` exactly.

**Idempotency:** if `revenue_recognition_schedules` already contains rows for the given
`invoiceId`, the existing rows are returned without creating duplicates.

### 4.3 Posting Run

The posting run is triggered either:
- **Automatically** by `RevenueRecognitionScheduler` (disabled by default, see §4.5)
- **Manually** via `POST /api/v2/admin/revenue-recognition/run?date=YYYY-MM-DD`

**Processing:**
1. Finds all rows with `status = PENDING` and `recognition_date <= date`
2. For each, posts a `REVENUE_RECOGNIZED` ledger entry in its own `REQUIRES_NEW`
   transaction (failure of one row does not affect others)
3. Marks the row `POSTED` and links the `ledger_entry_id`
4. Returns a summary DTO with `scheduled`, `posted`, `failed` counts and `failedScheduleIds`

**Failure policy:** a row that fails posting remains `PENDING` and will be retried on the
next run.  The schedule is NOT automatically moved to `FAILED`; this keeps retries
implicit and avoids manual state resets.

**Idempotency:** re-running the same date is safe — `POSTED` rows are skipped; only
`PENDING` rows with `recognition_date <= date` are processed.

### 4.4 Database Schema (`V24__revenue_recognition.sql`)

```sql
CREATE TABLE revenue_recognition_schedules (
    id                BIGSERIAL PRIMARY KEY,
    merchant_id       BIGINT         NOT NULL,
    subscription_id   BIGINT         NOT NULL,
    invoice_id        BIGINT         NOT NULL  REFERENCES invoices(id),
    recognition_date  DATE           NOT NULL,
    amount            NUMERIC(18,4)  NOT NULL,
    currency          VARCHAR(10)    NOT NULL DEFAULT 'INR',
    status            VARCHAR(32)    NOT NULL DEFAULT 'PENDING',
    ledger_entry_id   BIGINT         REFERENCES ledger_entries(id),
    created_at        TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_rev_recognition_due         ON revenue_recognition_schedules(recognition_date, status);
CREATE INDEX idx_rev_recognition_subscription ON revenue_recognition_schedules(subscription_id);
CREATE INDEX idx_rev_recognition_invoice      ON revenue_recognition_schedules(invoice_id);
```

### 4.5 Configuration

```properties
# Enable the daily scheduler (disabled by default)
revenue.recognition.scheduler.enabled=false

# Override the cron expression (default: 01:00 every day)
# revenue.recognition.scheduler.cron=0 0 1 * * *
```

---

## 5. API Reference

All endpoints are under `/api/v2/admin/revenue-recognition`.

### `GET /schedules`

Returns every row in `revenue_recognition_schedules`.

**Response:** `200 OK` — `List<RevenueRecognitionScheduleResponseDTO>`

### `POST /run?date=YYYY-MM-DD`

Posts all `PENDING` schedules whose `recognition_date` is on or before `date`.
Re-running the same date is fully idempotent.

**Response:** `200 OK` — `RevenueRecognitionRunResponseDTO`

```json
{
  "date": "2024-03-15",
  "scheduled": 30,
  "posted": 30,
  "failed": 0,
  "failedScheduleIds": []
}
```

### `GET /report?from=YYYY-MM-DD&to=YYYY-MM-DD`

Returns aggregated amounts and counts by status for schedules whose
`recognition_date` falls within `[from, to]` (inclusive).

**Response:** `200 OK` — `RevenueRecognitionReportDTO`

```json
{
  "from": "2024-03-01",
  "to": "2024-03-31",
  "postedAmount": "2970.00",
  "pendingAmount": "30.00",
  "failedAmount": "0.00",
  "postedCount": 29,
  "pendingCount": 1,
  "failedCount": 0
}
```

---

## 6. Key Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Recognisable amount | `grandTotal` (fallback to `totalAmount`) | Represents cash actually collected |
| Daily precision | Scale 4, HALF_UP | Sufficient for INR; last-day correction ensures exact sum |
| Failure policy | Leave PENDING | Implicit retry; no manual state resets needed |
| Scheduler default | Disabled | Avoids accidental runs in staging/test |
| Self-injection | `@Autowired @Lazy` | Enables `REQUIRES_NEW` transaction boundary for isolated per-row posting |
| Circular dependency | None | `RevenueRecognitionScheduleServiceImpl` injects `InvoiceRepository`, not `InvoiceService` |

---

## 7. India GST Considerations

> **Note:** The current implementation recognises the full `grandTotal` (inclusive of GST)
> as revenue.  In a production GST-compliant system, the recognisable *revenue* amount
> should be `grandTotal - taxTotal` to separate the tax collected on behalf of the
> government from true subscription revenue.  This separation is left for a future
> tax-aware recognition pass.

---

## 8. Testing

| Test class | Type | Coverage |
|---|---|---|
| `RevenueRecognitionScheduleServiceTest` | Unit (Mockito) | Schedule generation, idempotency, validation failures |
| `RevenueRecognitionPostingServiceTest`  | Unit (Mockito) | Batch posting, single-record posting, idempotency, failure propagation |
| `RevenueRecognitionControllerTest`      | Integration (Testcontainers Postgres) | All three controller endpoints, idempotent re-run, partial-date cutoff, report |
