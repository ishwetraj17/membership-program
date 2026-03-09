# Reconciliation 2.0 — Batch-Level, Three-Way, and Statement Import

## Overview

Phase 17 extends the existing single-layer recon (Invoice vs Payment) to a serious four-layer
finance reconciliation system covering every leg of money movement through the platform.

```
Layer 1  Invoice ↔ Payment          (original — RunForDate)
Layer 2  Payment gross ↔ Ledger settlement
Layer 3  Ledger settlement ↔ Settlement batch gross
Layer 4  Settlement batch gross ↔ External statement total
```

---

## Database Changes (V31)

| Table | Purpose |
|---|---|
| `settlement_batches` | Per-merchant, per-date batch of captured payments processed through a gateway |
| `settlement_batch_items` | One row per payment inside a batch, with fee/reserve/net breakdown |
| `external_statement_imports` | CSV uploads from gateways or banks; parsed and stored for cross-check |
| `recon_mismatches` (extended) | Added `status`, `owner_user_id`, `resolution_note` columns |

### Fee model (settlement batch)

```
fee     = payment.amount × 2%
reserve = payment.amount × 1%
net     = payment.amount − fee − reserve
```

---

## New Enums

| Enum | Values |
|---|---|
| `ReconMismatchStatus` | `OPEN`, `ACKNOWLEDGED`, `RESOLVED`, `IGNORED` |
| `SettlementBatchStatus` | `CREATED`, `POSTED`, `FAILED` |
| `StatementSourceType` | `GATEWAY`, `BANK` |
| `StatementImportStatus` | `PENDING`, `IMPORTED`, `FAILED` |

### Extended MismatchType

| Value | Layer | Description |
|---|---|---|
| `INVOICE_NO_PAYMENT` | 1 | Invoice exists but no captured payment |
| `PAYMENT_NO_INVOICE` | 1 | Payment captured but no invoice |
| `AMOUNT_MISMATCH` | 1 | Invoice and payment amounts differ |
| `DUPLICATE_GATEWAY_TXN` | 1 | Same gateway txn ID captured twice |
| `PAYMENT_LEDGER_VARIANCE` | 2 | Sum of captured payments ≠ ledger settlement total |
| `LEDGER_BATCH_VARIANCE` | 3 | Ledger settlement total ≠ settlement batch gross |
| `BATCH_STATEMENT_VARIANCE` | 4 | Settlement batch gross ≠ external statement total |

---

## API Endpoints

### Settlement Batches  `POST/GET /api/v2/admin/settlement-batches`

| Method | Path | Description |
|---|---|---|
| `POST` | `/run?merchantId=&date=&gatewayName=` | Create and post a settlement batch |
| `GET` | `/{batchId}` | Retrieve batch by ID |
| `GET` | `/{batchId}/items` | List payment items in a batch |
| `GET` | `/?merchantId=` | Paginated list of batches for a merchant |

### Statement Imports  `/api/v2/admin/recon/import-statement`

| Method | Path | Description |
|---|---|---|
| `POST` | `/` | Import external CSV (gateway or bank) |
| `GET` | `/?merchantId=` | List imports for a merchant |
| `GET` | `/{importId}` | Get import details |

**CSV format** (first row is header, skipped automatically):

```csv
txn_id,amount,currency,payment_date,reference
TXN-001,1000.00,INR,2025-01-15,PAY-001
TXN-002,2500.50,INR,2025-01-15,PAY-002
```

### Recon Mismatches  `/api/v2/admin/recon/mismatches`

| Method | Path | Description |
|---|---|---|
| `GET` | `/?status=OPEN` | List mismatches (all or filtered by status) |
| `POST` | `/{mismatchId}/acknowledge` | Assign owner, move to ACKNOWLEDGED |
| `POST` | `/{mismatchId}/resolve` | Provide resolution note, move to RESOLVED |
| `POST` | `/{mismatchId}/ignore` | Provide reason, move to IGNORED |

**Acknowledge request body:**
```json
{ "ownerUserId": 42 }
```

**Resolve request body:**
```json
{ "resolutionNote": "Refund issued to customer", "ownerUserId": 42 }
```

**Ignore request body:**
```json
{ "reason": "Known test data — not a real discrepancy" }
```

### Recon Reports  `/api/v2/admin/recon/reports`

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | Paginated list of all reconciliation reports |
| `GET` | `/{id}` | Get report with full mismatch list |

---

## Advanced Reconciliation Service

`AdvancedReconciliationService` provides the three-layer comparison methods:

```java
// Layer 2
List<ReconMismatchDTO> reconcilePaymentToLedger(LocalDate date, Long reportId)

// Layer 3
List<ReconMismatchDTO> reconcileLedgerToSettlementBatch(Long batchId, Long reportId)

// Layer 4
List<ReconMismatchDTO> reconcileSettlementBatchToStatement(Long batchId, Long importId, Long reportId)

// Lifecycle
ReconMismatchDTO acknowledgeMismatch(Long mismatchId, Long ownerUserId)
ReconMismatchDTO resolveMismatch(Long mismatchId, String resolutionNote, Long ownerUserId)
ReconMismatchDTO ignoreMismatch(Long mismatchId, String reason)
Page<ReconMismatchDTO> listMismatches(ReconMismatchStatus status, Pageable pageable)
```

**Tolerance:** Differences ≤ `0.01` (INR) are ignored — floating-point rounding margin.

---

## Mismatch Lifecycle State Machine

```
           ┌────────────────────────────────┐
           ▼                                │
         OPEN ──acknowledge──► ACKNOWLEDGED │
           │                      │         │
           │                    resolve     │
           │                      │         │
           │                      ▼         │
           └──────ignore──────► IGNORED   RESOLVED
```

All lifecycle transitions are idempotent at the service level.

---

## New Services

| Service | Responsibility |
|---|---|
| `SettlementBatchService` | Create/query per-merchant settlement batches |
| `StatementImportService` | Parse CSV and persist external statement imports |
| `AdvancedReconciliationService` | Layer 2–4 comparisons + mismatch lifecycle |

---

## Test Coverage

### Unit Tests
- `SettlementBatchServiceTest` — batch totals, empty date, not-found  
- `StatementImportServiceTest` — valid/empty/malformed CSV, pagination  
- `AdvancedReconciliationServiceTest` — all three comparison layers, full lifecycle  

### Integration Tests (Testcontainers Postgres)
- `SettlementBatchAdminControllerTest` — run batch, get, items, list, auth  
- `StatementImportAdminControllerTest` — import, list, get by ID, auth  
- `ReconMismatchAdminControllerTest` — list, lifecycle transitions, auth  

---

## Swagger

`com.firstclub.recon.controller` is now registered in `springdoc.packages-to-scan`,
so all four new controllers appear in the Swagger UI at `/swagger-ui.html`.
