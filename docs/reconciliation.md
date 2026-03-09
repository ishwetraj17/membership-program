# Reconciliation & Settlement

## Overview

The reconciliation and settlement module performs nightly financial close operations, ensuring that all invoiced amounts are matched against captured gateway payments, and that settled funds are reflected in the ledger.

---

## Architecture

```
Nightly (02:00)          Nightly (02:10)
      │                        │
      ▼                        ▼
SettlementService      ReconciliationService
      │                        │
      ▼                        ▼
LedgerService          recon_reports
PG_CLEARING→BANK       recon_mismatches
settlements table
```

---

## Database Schema

### `settlements`
| Column | Type | Description |
|---|---|---|
| id | BIGSERIAL | Primary key |
| settlement_date | DATE | Business date (unique) |
| total_amount | NUMERIC(14,2) | Sum of captured payments swept |
| currency | VARCHAR(10) | Always INR |
| created_at | TIMESTAMP | Row creation time |

### `recon_reports`
| Column | Type | Description |
|---|---|---|
| id | BIGSERIAL | Primary key |
| report_date | DATE | Business date (unique) |
| expected_total | NUMERIC(14,2) | Sum of invoices created on date |
| actual_total | NUMERIC(14,2) | Sum of captured payments on date |
| mismatch_count | INT | Number of discrepancy rows |
| created_at | TIMESTAMP | Row creation time |

### `recon_mismatches`
| Column | Type | Description |
|---|---|---|
| id | BIGSERIAL | Primary key |
| report_id | BIGINT | FK → recon_reports |
| type | VARCHAR(40) | Mismatch category (enum) |
| invoice_id | BIGINT nullable | Related invoice |
| payment_id | BIGINT nullable | Related payment |
| details | TEXT | Human-readable description |

---

## Mismatch Types

| Type | Description |
|---|---|
| `INVOICE_NO_PAYMENT` | Invoice was created but no captured payment found |
| `PAYMENT_NO_INVOICE` | Captured payment has no matching invoice |
| `AMOUNT_MISMATCH` | Invoice total ≠ sum of linked payments |
| `DUPLICATE_GATEWAY_TXN` | Multiple payments share the same `gatewayTxnId` |

---

## Nightly Schedule

| Job | Cron | Action |
|---|---|---|
| Settlement sweep | `0 0 2 * * *` | Move PG_CLEARING → BANK via ledger entry |
| Reconciliation report | `0 10 2 * * *` | Generate mismatches for yesterday |

---

## Admin REST API

### Get JSON report
```
GET /api/v1/admin/recon/daily?date=2025-01-15
Authorization: Bearer {admin-token}
```

Response includes `expectedTotal`, `actualTotal`, `variance`, `mismatchCount`, and an array of `mismatches`.

### Download CSV
```
GET /api/v1/admin/recon/daily.csv?date=2025-01-15
Authorization: Bearer {admin-token}
```

Returns `Content-Disposition: attachment; filename="recon-2025-01-15.csv"`.

### Trigger settlement
```
POST /api/v1/admin/recon/settle?date=2025-01-15
Authorization: Bearer {admin-token}
```

Idempotent — returns existing settlement if one already exists for the date.

---

## Settlement Ledger Flow

Settlement moves funds from the payment gateway clearing account to the merchant bank account:

```
DEBIT  BANK          +total   (asset rises — funds arrive)
CREDIT PG_CLEARING   -total   (clearing balance decreases)
```

The entry type is `SETTLEMENT` and the reference type is `SETTLEMENT_BATCH (→ settlements.id)`.

---

## On-Demand Reconciliation

Calling `GET /api/v1/admin/recon/daily` for a date that has no existing report triggers an immediate reconciliation run. This is idempotent — a second call returns the cached report. A re-run can be triggered by calling `DELETE` (not implemented) or re-posting.

---

## Testing

- `ReconciliationServiceTest` — 6 unit tests covering all 4 mismatch types + clean run + idempotent re-run.  
- `SettlementServiceTest` — 4 unit tests covering balanced ledger entry, zero settlement, and idempotency.
