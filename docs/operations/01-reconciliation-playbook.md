# Reconciliation Playbook

This playbook guides operators through the nightly reconciliation process: how it runs, how to interpret results, how to investigate mismatches, and how to resolve them.

---

## 1. How Reconciliation Runs

### Automatic Nightly Schedule

| Job | Cron | Description |
|---|---|---|
| Settlement | `0 0 2 * * *` (02:00 UTC) | Aggregates captured payments into settlement batches; posts SETTLEMENT ledger entries |
| Reconciliation | `0 10 2 * * *` (02:10 UTC) | Runs 4-layer comparison; inserts `ReconMismatch` rows |
| Ledger Snapshot | `0 30 3 * * *` (03:30 UTC) | Materializes `ledger_balance_snapshots` for the previous day |

### Manual Trigger

If the nightly job failed or you need to re-run for a specific date:

```bash
# Trigger settlement for a specific date
POST /api/v1/admin/recon/settle?date=2025-01-15

# Get reconciliation report as JSON
GET /api/v1/admin/recon/daily?date=2025-01-15

# Download as CSV for finance team
GET /api/v1/admin/recon/daily.csv?date=2025-01-15
```

Authentication required: `ADMIN` role.

---

## 2. Reading the Report

### JSON Response Structure

```json
{
  "date": "2025-01-15",
  "status": "COMPLETED",
  "batchId": "uuid-...",
  "summary": {
    "layer1Mismatches": 2,
    "layer2Mismatches": 0,
    "layer3Mismatches": 1,
    "layer4Mismatches": 0,
    "totalMismatches": 3
  },
  "mismatches": [
    {
      "id": "uuid-...",
      "layer": 1,
      "type": "INVOICE_NO_PAYMENT",
      "referenceType": "INVOICE",
      "referenceId": "inv-uuid-...",
      "expectedAmount": 499.00,
      "actualAmount": 0.00,
      "delta": -499.00,
      "status": "OPEN"
    }
  ]
}
```

---

## 3. Mismatch Types and Investigation Steps

### Layer 1 Mismatches

#### `INVOICE_NO_PAYMENT`

**Meaning:** An invoice is in `PENDING` or `PAST_DUE` status with no matching `SUCCEEDED` payment.

**Investigation:**
```sql
SELECT i.id, i.invoice_number, i.status, i.grand_total, i.due_date,
       s.status AS subscription_status
FROM invoices i
LEFT JOIN subscriptions_v2 s ON s.id = i.subscription_id
WHERE i.id = :invoiceId;

-- Check payment attempts
SELECT pa.id, pa.attempt_number, pa.status, pa.gateway_response, pa.created_at
FROM payment_attempts_v2 pa
JOIN payment_intents_v2 pi ON pi.id = pa.intent_id
WHERE pi.invoice_id = :invoiceId
ORDER BY pa.attempt_number;
```

**Resolution options:**
- Dunning engine should handle this automatically (check dunning_attempts)
- If dunning is exhausted: manually cancel the subscription via `POST /api/v2/subscriptions/{id}/cancel`
- If payment was made outside the system: post a manual payment record (requires engineering)

#### `PAYMENT_NO_INVOICE`

**Meaning:** A captured payment has no linked invoice.

**Investigation:**
```sql
SELECT pi.id, pi.invoice_id, pi.captured_amount, pi.gateway_txn_id, pi.created_at
FROM payment_intents_v2 pi
WHERE pi.id = :paymentId;
```

**This is likely a bug.** A payment that succeeded without a valid invoice should not exist. Escalate to engineering.

#### `AMOUNT_MISMATCH`

**Meaning:** The payment amount does not equal the invoice grand_total.

**Investigation:**
```sql
SELECT i.grand_total, pi.captured_amount
FROM invoices i
JOIN payment_intents_v2 pi ON pi.invoice_id = i.id
WHERE i.id = :invoiceId;
```

**Causes:**
- Partial payment (gateway only captured part of the amount) — contact gateway
- Currency conversion rounding — check gateway response
- Invoice was updated after payment intent was created — check audit log

#### `DUPLICATE_GATEWAY_TXN`

**Meaning:** The same `gateway_txn_id` appears on multiple payment records.

**Investigation:**
```sql
SELECT id, gateway_txn_id, invoice_id, captured_amount, status
FROM payment_intents_v2
WHERE gateway_txn_id = :txnId;
```

**This is a critical bug.** Idempotency guards should prevent this. If it exists: check `idempotency_keys` for the callback key; check `business_fingerprint` on `payment_attempts_v2`.

---

### Layer 2 Mismatches: `PAYMENT_LEDGER_MISMATCH`

**Meaning:** Sum of captured payments ≠ sum of PAYMENT_CAPTURED ledger entries.

**Investigation:**
```sql
-- Find payments missing a ledger entry
SELECT pi.id, pi.captured_amount, pi.gateway_txn_id
FROM payment_intents_v2 pi
WHERE pi.status = 'SUCCEEDED'
  AND DATE(pi.updated_at) = :targetDate
  AND NOT EXISTS (
    SELECT 1 FROM ledger_entries le
    WHERE le.reference_type = 'PAYMENT'
      AND le.reference_id = pi.id::text
      AND le.entry_type = 'PAYMENT_CAPTURED'
  );
```

**Resolution:** For each payment missing a ledger entry, the `LedgerServiceImpl.postEntry()` needs to be called manually with the correct amounts. This is a repair action — escalate to engineering.

---

### Layer 3 Mismatches: `SETTLEMENT_LEDGER_MISMATCH`

**Meaning:** Settlement batch amount ≠ settlement ledger entries.

**Investigation:**
```sql
SELECT sb.gross_amount, sb.gateway, sb.batch_date,
       SUM(ll.amount) AS ledger_settlement_amount
FROM settlement_batches sb
LEFT JOIN ledger_entries le ON le.reference_type = 'SETTLEMENT_BATCH' AND le.reference_id = sb.id::text
LEFT JOIN ledger_lines ll ON ll.entry_id = le.id
WHERE sb.batch_date = :targetDate
GROUP BY sb.id, sb.gross_amount, sb.gateway;
```

---

### Layer 4 Mismatches: `BANK_STATEMENT_MISMATCH`

**Meaning:** Settlement batch amount ≠ bank statement line.

**Investigation:** Download bank statement CSV; compare with settlement batch amounts by gateway and date. Discrepancies may indicate gateway processing delays (money in transit — should auto-resolve in 1–2 business days) or genuine shortfall.

---

## 4. Resolving Mismatches

### Mark as RESOLVED

After root cause is identified and addressed:

```
PATCH /api/v1/admin/recon/mismatches/{mismatchId}
{
  "status": "RESOLVED",
  "resolutionNote": "Dunning recovered payment on Jan 16"
}
```

### Mark as IGNORED

For known false positives (e.g., a payment that cleared the next business day):

```
PATCH /api/v1/admin/recon/mismatches/{mismatchId}
{
  "status": "IGNORED",
  "resolutionNote": "Cross-day settlement timing difference; expected"
}
```

**SLA:** All `OPEN` mismatches should be resolved or ignored within 3 business days. Mismatches older than 7 days require escalation to a finance lead.

---

## 5. When Reconciliation Fails (Job Fails)

**Symptom:** `recon_batches` row for today has `status = FAILED`; no mismatches inserted.

**Step 1:** Check app logs for the reconciliation job run time.

```bash
grep "reconciliation" /var/log/membership/app.log | grep ERROR | tail -20
```

**Step 2:** Check deep health.

```
GET /ops/health/deep
```

**Step 3:** Re-trigger manually after fixing root cause.

```
POST /api/v1/admin/recon/settle?date=YYYY-MM-DD
```

**Step 4:** If the job lock is stuck (job crashed mid-run, lock not released):

```sql
-- Check lock
SELECT * FROM job_locks WHERE name = 'reconciliation_daily';

-- Release if truly stuck (confirm the previous job is not actually still running)
UPDATE job_locks SET locked_by = NULL, locked_at = NULL WHERE name = 'reconciliation_daily';
```

---

## 6. Escalation Checklist

Escalate to engineering if:
- `PAYMENT_NO_INVOICE` mismatch is found (should not be possible)
- `DUPLICATE_GATEWAY_TXN` is found (idempotency guard failure)
- Layer 2 mismatch: any payment missing a ledger entry
- Reconciliation job fails for more than 2 consecutive nights
- DISPUTE_RESERVE ledger account balance goes negative


---

## Phase 20 — Deep Health Integration with Reconciliation

### New `integrityViolationCount` Field

The deep health endpoint (`GET /api/v2/admin/system/health/deep`) now includes:

- `integrityViolationCount` — number of `IntegrityCheckFinding` records with status `FAIL`
- `integrityLastRunStatus` — status of the most recent `IntegrityCheckRun` (e.g. `COMPLETED`, `FAILED`, `NONE`)

When `integrityViolationCount > 0`, the `overallStatus` is set to `DEGRADED`.

### Alert Conditions

| Condition | Severity | Action |
|-----------|----------|--------|
| `integrityViolationCount > 0` | HIGH | Run integrity check review; see 05-manual-repair-actions.md |
| `integrityLastRunStatus == FAILED` | HIGH | Check scheduler logs; re-trigger manual run |
| `integrityLastRunStatus == NONE` | MEDIUM | Integrity checker has never run; verify scheduled job registration |
| `reconMismatchOpenCount > 0` | HIGH | Follow standard recon mismatch escalation ladder |

### Using the System Summary for Recon Monitoring

`GET /api/v2/admin/system/summary` returns `reconMismatchOpenCount` alongside all other
operational counters. Use this as the primary dashboard metric for recon health,
complementing the deep health status indicator.
