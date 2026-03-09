# Phase 16 — Projection Tables and Snapshot-Based Reads

## Problem

Admin and reporting reads (customer billing summaries, merchant KPIs, ledger
balances) previously scanned the hot transactional tables on every request:

* `invoices` — unbounded full-table aggregations per merchant
* `ledger_lines` — cross-joined with `ledger_accounts` to compute running balances
* `subscriptions` — filtered scans for active counts

At scale, these reads compete directly with write transactions and add latency
to the OLTP path.

---

## Solution

Introduce **read-model projection tables** that are maintained asynchronously
from the domain event log, plus **snapshot tables** for point-in-time captures.

Reads hit the projection/snapshot tables only — no joins against `invoices`,
`ledger_lines`, or `subscriptions`.

---

## New Tables (V30 migration)

### `customer_billing_summary_projection`

Composite PK: `(merchant_id, customer_id)`

| Column | Type | Description |
|---|---|---|
| `merchant_id` | BIGINT | Tenant |
| `customer_id` | BIGINT | Customer |
| `active_subscriptions_count` | INT | Live subscriptions for this customer |
| `unpaid_invoices_count` | INT | Invoices raised but not yet paid |
| `total_paid_amount` | DECIMAL(18,4) | Lifetime payments captured |
| `total_refunded_amount` | DECIMAL(18,4) | Lifetime refunds issued |
| `last_payment_at` | TIMESTAMP | Most recent payment timestamp |
| `updated_at` | TIMESTAMP | Auto-updated on each write |

### `ledger_balance_snapshots`

PK: `id` (BIGSERIAL)  
Platform snapshots: `merchant_id IS NULL`, uniquely constrained via partial index on `(account_id, snapshot_date)`.  
Merchant-specific snapshots (future): `merchant_id IS NOT NULL`, uniquely constrained via partial index on `(merchant_id, account_id, snapshot_date)`.

| Column | Type | Description |
|---|---|---|
| `id` | BIGSERIAL | Surrogate key |
| `merchant_id` | BIGINT | NULL for platform snapshots |
| `account_id` | BIGINT | FK → `ledger_accounts.id` |
| `snapshot_date` | DATE | Point-in-time date |
| `balance` | DECIMAL(18,4) | Balance at snapshot time |
| `created_at` | TIMESTAMP | Row creation time |

### `merchant_daily_kpis_projection`

Composite PK: `(merchant_id, business_date)`

| Column | Type | Description |
|---|---|---|
| `merchant_id` | BIGINT | Tenant |
| `business_date` | DATE | Calendar day of the KPIs |
| `invoices_created` | INT | Invoices raised on this day |
| `invoices_paid` | INT | Invoices settled on this day |
| `payments_captured` | INT | Successful payment captures |
| `refunds_completed` | INT | Completed refunds |
| `disputes_opened` | INT | New chargebacks/disputes |
| `revenue_recognized` | DECIMAL(18,4) | Payment amounts captured |
| `updated_at` | TIMESTAMP | Auto-updated on each write |

---

## Event → Projection Mapping

### `customer_billing_summary_projection`

All events must carry `merchantId` (on the `DomainEvent` entity) and a
`customerId` field in the JSON payload.

| Event type | Effect |
|---|---|
| `INVOICE_CREATED` | `unpaid_invoices_count++` |
| `PAYMENT_SUCCEEDED` | `total_paid_amount += amount`, `unpaid_invoices_count--`, `last_payment_at = now` |
| `REFUND_COMPLETED` / `REFUND_ISSUED` | `total_refunded_amount += amount` |
| `SUBSCRIPTION_ACTIVATED` | `active_subscriptions_count++` |
| `SUBSCRIPTION_CANCELLED` / `SUBSCRIPTION_SUSPENDED` | `active_subscriptions_count--` (floor 0) |

### `merchant_daily_kpis_projection`

Events must carry `merchantId`. Business date is derived from `event.createdAt`.

| Event type | Effect |
|---|---|
| `INVOICE_CREATED` | `invoices_created++` |
| `PAYMENT_SUCCEEDED` | `invoices_paid++`, `payments_captured++`, `revenue_recognized += amount` |
| `REFUND_COMPLETED` / `REFUND_ISSUED` | `refunds_completed++` |
| `DISPUTE_OPENED` | `disputes_opened++` |

---

## Architecture

```
Write path
──────────────────────────────────────────────────────────
 Service → DomainEventLog.record() → domain_events (append-only)
                     │
                     └─ publishes DomainEventRecordedEvent (Spring ApplicationEvent)
                                   │
                              [ASYNC thread pool]
                                   │
                         ProjectionEventListener
                                   │
                    ┌──────────────┴──────────────┐
                    ▼                             ▼
   applyEventToCustomerBillingProjection   applyEventToMerchantDailyKpi
                    │                             │
        customer_billing_summary_    merchant_daily_kpis_
        projection (upsert)          projection (upsert)

Read path (fast)
──────────────────────────────────────────────────────────
 GET /api/v2/admin/projections/customer-billing-summary
    → SELECT FROM customer_billing_summary_projection
    → no joins, index-backed, O(1) per row

Snapshot path (scheduled / on-demand)
──────────────────────────────────────────────────────────
 DailySnapshotScheduler  OR  POST /api/v2/admin/ledger/balance-snapshots/run
    → LedgerSnapshotService.generateSnapshotForDate(date)
    → LedgerService.getBalances()  (single pass over ledger_lines)
    → INSERT INTO ledger_balance_snapshots (idempotent)

 GET /api/v2/admin/ledger/balance-snapshots
    → SELECT FROM ledger_balance_snapshots  (no ledger_lines touch)
```

---

## Component Inventory

### Main-path classes

| Class | Package | Role |
|---|---|---|
| `ProjectionUpdateService` | `reporting.projections.service` | Applies one `DomainEvent` to both projections; `@Transactional`, NOT async |
| `LedgerSnapshotService` | `reporting.projections.service` | Idempotent snapshot generation + list retrieval |
| `ProjectionRebuildService` | `reporting.projections.service` | Truncate-and-replay for full projection rebuilds |
| `ProjectionEventListener` | `reporting.projections.listener` | `@EventListener @Async` — wires domain events → `ProjectionUpdateService` |
| `DailySnapshotScheduler` | `reporting.projections.scheduler` | Cron-driven daily snapshot (disabled by default) |
| `ProjectionAdminController` | `reporting.projections.controller` | REST: GET projections, POST rebuild |
| `LedgerSnapshotController` | `reporting.projections.controller` | REST: POST run snapshot, GET snapshots |
| `DomainEventRecordedEvent` | `events.event` | Spring `ApplicationEvent` published by `DomainEventLog` after each save |

### Entity / DTO / Repository classes

| Entity | ID class | Table |
|---|---|---|
| `CustomerBillingSummaryProjection` | `CustomerBillingProjectionId` | `customer_billing_summary_projection` |
| `MerchantDailyKpiProjection` | `MerchantKpiProjectionId` | `merchant_daily_kpis_projection` |
| `LedgerBalanceSnapshot` | (BIGSERIAL PK) | `ledger_balance_snapshots` |

---

## API Reference

### Projection reads

```
GET /api/v2/admin/projections/customer-billing-summary
  ?merchantId=<Long>     (optional)
  ?customerId=<Long>     (optional; requires merchantId)
  ?page=0&size=50        (Spring Pageable)

GET /api/v2/admin/projections/merchant-kpis/daily
  ?merchantId=<Long>     (optional)
  ?from=YYYY-MM-DD       (optional)
  ?to=YYYY-MM-DD         (optional)
  ?page=0&size=50
```

### Projection rebuild (destructive)

```
POST /api/v2/admin/projections/rebuild/{projectionName}
  Supported: customer_billing_summary | merchant_daily_kpi

Response: { projectionName, eventsProcessed, recordsInProjection, rebuiltAt }
```

### Ledger snapshots

```
POST /api/v2/admin/ledger/balance-snapshots/run?date=YYYY-MM-DD
  Captures current ledger balances for the given date. Idempotent.
  Defaults to today when date is omitted.

GET /api/v2/admin/ledger/balance-snapshots
  ?from=YYYY-MM-DD    (optional)
  ?to=YYYY-MM-DD      (optional)
  ?merchantId=<Long>  (optional; NULL = platform snapshots)
```

All endpoints require `ADMIN` role.

---

## Configuration

```properties
# Projection snapshot scheduler — disabled by default
projections.snapshot.scheduler.enabled=false
# projections.snapshot.scheduler.cron=0 0 1 * * *   # daily 01:00
```

Enable the scheduler in production (or via environment variable override):

```properties
projections.snapshot.scheduler.enabled=true
```

---

## Rebuild vs Live Updates

Use **rebuild** when:
* Projection tables are first populated after deployment
* A bug in `ProjectionUpdateService` corrupted data and has since been fixed
* The event schema changed and projections need re-derivation

**Live async updates** (via `ProjectionEventListener`) keep projections in
near-real-time sync during normal operation. Failures are logged; projections
can always be rebuilt to recover.

> **Note:** Rebuild loads all events via `DomainEventRepository.findAll()`.
> For very large event logs, switch to a paginated scan before enabling in
> a high-volume production system.

---

## Consistency Guarantees

* Projections are **eventually consistent** — the async listener may lag the
  write path by up to a few hundred milliseconds.
* For reports that need exact current-state values, call the rebuild endpoint
  and then read the projection immediately after.
* `LedgerBalanceSnapshot` rows are immutable once created; idempotency is
  enforced at the service layer (code check + partial DB unique index in prod).

---

*Phase 16 — implemented by Shwet Raj*
