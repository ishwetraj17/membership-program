# Phase 11 — Integrity Check Playbook

## Overview

The invariant engine runs a battery of 15 cross-entity correctness checks across the entire platform and persists each result to `integrity_check_runs` / `integrity_check_results`.  Use this playbook during on-call incidents, post-deploy verification, and regular auditing.

---

## 1. Trigger a Check

```bash
# One-off manual check (requires ADMIN role)
curl -X POST "https://api.example.com/api/v1/admin/integrity/check?triggered_by=oncall-alice" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "X-Request-Id: $(uuidgen)"
```

The response is an `IntegrityCheckRunResponseDTO` showing:
- `id` — run identifier  
- `status` — `COMPLETED` (all pass) or `FAILED` (at least one violation or error)  
- `total_checkers`, `failed_checkers`, `error_checkers`  
- `results[]` — per-invariant name, status, violation_count, severity, affected_entities, suggested_repair_action

---

## 2. Fetch a Previous Run

```bash
curl "https://api.example.com/api/v1/admin/integrity/check-runs/{id}" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

---

## 3. Invariant Reference

| # | Invariant Name | Severity | Description |
|---|---|---|---|
| 1 | `BALANCE_SHEET_EQUATION` | CRITICAL | Total debits across all ledger lines must equal total credits |
| 2 | `DEFERRED_REVENUE_NON_NEGATIVE` | HIGH | SUBSCRIPTION_LIABILITY account must not have a net-credit (negative deferred) balance |
| 3 | `REVENUE_RECOGNITION_CEILING` | HIGH | Sum of recognition schedule amounts must not exceed the invoice grand total |
| 4 | `PAYMENT_HAS_SINGLE_LEDGER_ENTRY` | HIGH | Every CAPTURED payment must have exactly one PAYMENT_CAPTURED ledger entry |
| 5 | `REFUND_AMOUNT_CHAIN` | CRITICAL | refundedAmount + disputedAmount must not exceed capturedAmount |
| 6 | `RECOGNITION_SCHEDULE_COMPLETENESS` | MEDIUM | Every PAID subscription invoice must have a revenue recognition schedule |
| 7 | `NO_FUTURE_LEDGER_ENTRY` | HIGH | No ledger entry should have a createdAt timestamp in the future |
| 8 | `INVOICE_PAYMENT_AMOUNT_CONSISTENCY` | HIGH | Invoice grandTotal must equal max(subtotal − discountTotal − creditTotal + taxTotal, 0) |
| 9 | `DISPUTE_RESERVE_COMPLETENESS` | HIGH | Every OPEN/UNDER_REVIEW dispute must have reservePosted = true |
| 10 | `SETTLEMENT_LEDGER_COMPLETENESS` | MEDIUM | Every SETTLEMENT ledger entry must have a non-null referenceId |
| 11 | `ASSET_ACCOUNT_NON_NEGATIVE` | HIGH | No ASSET ledger account may have a negative net balance (total debits < total credits) |
| 12 | `NO_ORPHAN_LEDGER_LINE` | HIGH | Every LedgerLine must reference an existing LedgerEntry |
| 13 | `OUTBOX_TO_LEDGER_GAP` | MEDIUM | There must be no permanently FAILED outbox events (potential ledger posting gaps) |
| 14 | `WEBHOOK_DUPLICATE_PROCESSING` | MEDIUM | No valid unprocessed webhook should have ≥ 10 failed attempts (stuck/unprocessed) |
| 15 | `SUBSCRIPTION_INVOICE_PERIOD_OVERLAP` | MEDIUM | No two invoices for the same subscription may have overlapping billing periods |

---

## 4. Severity Meanings

| Severity | Meaning | Response SLA |
|---|---|---|
| `CRITICAL` | Platform-wide correctness is broken (balance sheet unbalanced, refund overflow) | Immediate — page on-call |
| `HIGH` | Significant ledger or billing inconsistency affecting individual entities | Same business day |
| `MEDIUM` | Operational gap that may affect reconciliation but not immediate customer impact | Within 3 business days |
| `LOW` | Informational warning | On next scheduled review |

---

## 5. Common Repair Procedures

### BALANCE_SHEET_EQUATION fails

1. Query the DB for the imbalance delta: `SELECT SUM(amount) FILTER (WHERE direction='DEBIT') - SUM(amount) FILTER (WHERE direction='CREDIT') FROM ledger_lines`.
2. Find the unbalanced journal entry by looking for `LedgerEntry` rows where the sum of its `LedgerLine.direction=DEBIT` ≠ sum of `CREDIT`.
3. Post a correcting entry with `reversalOfEntryId = <original>` to zero the difference, then validate the equation passes.

### REFUND_AMOUNT_CHAIN fails

1. Identify the payment ID from `affected_entities`.
2. Pull all `Refund` and `Dispute` records linked to that payment.
3. If a refund row was duplicated: void the duplicate via `RefundService.voidRefund(id)` and reverse the ledger entry.
4. Re-run the check to confirm the violation is resolved.

### DISPUTE_RESERVE_COMPLETENESS fails

1. Retrieve the dispute ID from `affected_entities`.
2. Call `DisputeAccountingService.postReserve(disputeId)` in a database transaction.
3. Confirm that `DISPUTE_RESERVE_DEBIT` and `DISPUTE_RESERVE_CREDIT` ledger entries exist and the dispute row now has `reservePosted = true`.

### OUTBOX_TO_LEDGER_GAP fails

1. Check whether the downstream consumer processed the event through another path (dead-letter queue, manual replay).
2. If not, re-publish via `OutboxService.retry(eventId)`.
3. After the event is processed, verify the expected ledger entries exist.
4. Mark the outbox event status as `DONE`.

### NO_ORPHAN_LEDGER_LINE fails

1. Identify orphan lines via the `affected_entities` list.
2. Attempt to recover the parent `LedgerEntry` from the event log or DB backup.
3. If entry is unrecoverable, evaluate whether the line represents real economic activity; if not, archive and remove it (requires change-control approval).
4. Re-enable FK constraint on `ledger_lines.entry_id` to prevent future orphans.

---

## 6. Scheduling Recommendations

```yaml
# Example Spring @Scheduled configuration — run nightly at 02:00 UTC
# Add to a dedicated ScheduledIntegrityCheckService
@Scheduled(cron = "0 0 2 * * *")
void scheduledIntegrityCheck() {
    integrityCheckService.runCheck("scheduler", null, null);
}
```

Run after every major deploy, weekly during normal operations, and immediately when payment anomalies are reported.

---

## 7. Database Tables

```
integrity_check_runs
  id BIGINT PK
  started_at TIMESTAMP
  completed_at TIMESTAMP
  status VARCHAR(32)       -- RUNNING | COMPLETED | FAILED | ERROR
  triggered_by VARCHAR(128)
  request_id VARCHAR(64)
  correlation_id VARCHAR(64)

integrity_check_results
  id BIGINT PK
  run_id BIGINT FK → integrity_check_runs(id)
  invariant_name VARCHAR(128)
  status VARCHAR(16)       -- PASS | FAIL | ERROR
  violation_count INT
  severity VARCHAR(16)     -- CRITICAL | HIGH | MEDIUM | LOW
  details_json TEXT
  suggested_repair_action TEXT
  created_at TIMESTAMP
```

---

## 8. Adding a New Invariant

1. Create a `@Component` class implementing `InvariantChecker` in `com.firstclub.integrity.checkers`.
2. Implement `getName()` (unique SCREAMING_SNAKE_CASE constant), `getSeverity()`, and `check()`.
3. Add the invariant name + repair suggestion to `IntegrityRepairSuggestionService.SUGGESTIONS`.
4. Add unit tests for pass and fail paths.
5. The checker is automatically discovered by `InvariantEngine` via Spring's `List<InvariantChecker>` injection.
