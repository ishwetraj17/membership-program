# 13 — Read Models and Projections

## Overview

The Membership Platform maintains a set of *denormalized read-model projections* that are derived from the event log and kept in near-real-time sync with the transactional tables.  These projections exist solely to serve fast, zero-join query paths for dashboards, operations tooling, and reporting APIs — they are **never** the source of truth.

Any projection can be fully rebuilt at any time by calling the relevant rebuild endpoint on `ProjectionAdminController` or `ReportingController`.

---

## Projection Inventory

### Pre-Phase 19 Projections

| Table | Package | PK | Purpose |
|---|---|---|---|
| `customer_billing_summary_projection` | `reporting.projections` | `(merchant_id, customer_id)` | Subscription counts, paid/refunded BigDecimal amounts |
| `merchant_daily_kpis_projection` | `reporting.projections` | `(merchant_id, business_date)` | Per-day KPI buckets (invoices, payments, revenue, disputes) |
| `subscription_status_projection` | `reporting.ops` | `(merchant_id, subscription_id)` | Subscription lifecycle state with dunning/invoice counts |
| `invoice_summary_projection` | `reporting.ops` | `(merchant_id, invoice_id)` | Invoice totals, paid_at, overdue flag |
| `payment_summary_projection` | `reporting.ops` | `(merchant_id, payment_intent_id)` | Payment intent outcome with attempt count and refund totals |
| `recon_dashboard_projection` | `reporting.ops` | `(merchant_id, business_date)` | Reconciliation mismatch counts per date |

### Phase 19 Projections

| Table | Package | PK | Purpose |
|---|---|---|---|
| `customer_payment_summary_projection` | `reporting.projections` | `(merchant_id, customer_id)` | Minor-unit charged/refunded totals and success/failure counts |
| `ledger_balance_projection` | `reporting.projections` | `(merchant_id, user_id)` | Continuous ledger balance with credit/debit minor-unit accumulators |
| `merchant_revenue_projection` | `reporting.projections` | `merchant_id` | All-time revenue, refunds, net revenue, and subscription churn counters |

---

## Update Path

Every domain event that materialized in the event log flows into the projection pipeline via a two-step mechanism:

```
Domain write → DomainEvent saved → DomainEventRecordedEvent published
                                          │
                                 ProjectionEventListener (Async)
                                          │
                          ┌──────────────┴──────────────────┐
                          │                                  │
               ProjectionUpdateService            OpsProjectionUpdateService
               (reporting.projections)             (reporting.ops)
                          │
              ┌───────────┼────────────────┐
              │           │                │
  CustomerBilling   MerchantDailyKpi   Phase19Projections
     Summary         Projection       (CustomerPaymentSummary,
                                       LedgerBalance, MerchantRevenue)
```

The listener is `@Async` so projection updates never block the originating transaction.  If a listener call fails, the exception is logged but **does not roll back** the originating write — projections are eventually consistent.

---

## Rebuild / Recovery

Any projection can be fully reconstructed from scratch using the admin endpoints:

```
POST /api/v2/admin/projections/rebuild/{projectionName}
POST /reporting/projections/rebuild?projectionName={name}
```

The rebuild is **destructive** — all rows in the target table are deleted before replaying events or scanning source tables.

| Projection | Rebuild source |
|---|---|
| `customer_billing_summary` | Domain event replay |
| `merchant_daily_kpi` | Domain event replay |
| `subscription_status` | Live `subscription_v2` scan |
| `invoice_summary` | Live `invoice` scan |
| `payment_summary` | Live `payment_intent_v2` scan |
| `recon_dashboard` | Live `recon_report` scan |
| `customer_payment_summary` | Domain event replay |
| `ledger_balance` | Domain event replay |
| `merchant_revenue` | Domain event replay |

---

## Staleness Monitoring (`ProjectionLagMonitor`)

`ProjectionLagMonitor` computes the lag (in seconds) between the oldest `updated_at` value in each projection table and the current wall-clock time.

```java
ProjectionLagReport report = lagMonitor.getLag("customer_payment_summary");
boolean stale = report.isStale(300); // true if oldest row is > 5 min old
```

The lag for all projections can be retrieved via:

```
GET /api/v2/admin/projections/lag
GET /api/v2/admin/projections/lag/{projectionName}
```

An empty projection table returns `lagSeconds = -1` and is never considered stale (it may simply have no data yet).

---

## Consistency Checking (`ProjectionConsistencyChecker`)

`ProjectionConsistencyChecker` samples a specific projection row and compares a key counter against the live source table.  This is a diagnostic tool — it is not called in the payment hot path.

| Endpoint | Projection checked | Source table | Field compared |
|---|---|---|---|
| `GET /api/v2/admin/projections/consistency/customer-payment-summary` | `customer_payment_summary_projection` | `payment_intent_v2` | `successfulPayments` |
| `GET /api/v2/admin/projections/consistency/merchant-revenue` | `merchant_revenue_projection` | `subscription_v2` | `activeSubscriptions` |

A `ConsistencyReport` is returned with:

```json
{
  "projectionName": "customer_payment_summary",
  "key": "merchant=1,customer=42",
  "consistent": false,
  "projectionValue": 3,
  "sourceValue": 5,
  "delta": 2,
  "checkedField": "successfulPayments"
}
```

`delta > 0` means the projection is behind (events not yet applied).  `delta < 0` would indicate a double-count bug.

---

## API Reference

### Public Reporting Endpoints (`/reporting`)

All endpoints require `ADMIN` role.

| Method | Path | Description |
|---|---|---|
| `GET` | `/reporting/customer-payment-summary/{customerId}?merchantId=` | Single customer payment summary |
| `GET` | `/reporting/customer-payment-summary?merchantId=` | Paginated list for a merchant |
| `GET` | `/reporting/ledger-balance/{userId}?merchantId=` | User ledger balance |
| `GET` | `/reporting/merchant-revenue/{merchantId}` | Merchant all-time revenue |
| `POST` | `/reporting/projections/rebuild?projectionName=` | Rebuild a Phase 19 projection |

### Admin Projection Endpoints (`/api/v2/admin/projections`)

All endpoints require `ADMIN` role.

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v2/admin/projections/lag` | Lag for all 9 projections |
| `GET` | `/api/v2/admin/projections/lag/{projectionName}` | Lag for a single projection |
| `GET` | `/api/v2/admin/projections/consistency/customer-payment-summary` | Consistency check for customer payment summary |
| `GET` | `/api/v2/admin/projections/consistency/merchant-revenue` | Consistency check for merchant revenue |
| `POST` | `/api/v2/admin/projections/rebuild/{projectionName}` | Rebuild any supported projection |

---

## Design Principles

1. **Projections are never the authority** — all business logic reads from transactional tables; projections serve dashboards and reporting only.
2. **Async updates** — the `ProjectionEventListener` runs on a separate thread pool. Failures are logged; the originating transaction is unaffected.
3. **Full rebuildability** — every projection can be reconstructed in `O(n)` event/source-table scans.
4. **Lag monitoring** — `ProjectionLagMonitor` enables alerting on stale projections without polling every row.
5. **Consistency sampling** — `ProjectionConsistencyChecker` provides lightweight spot-checks for projection correctness without full reconciliation.
