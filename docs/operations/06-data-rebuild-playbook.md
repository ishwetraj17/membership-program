# Data Rebuild Playbook

This document covers how to rebuild derived or materialized data when the underlying source-of-truth tables are intact but secondary data (projections, snapshots, schedules, outbox backlog) is corrupted, missing, or out of sync.

**Golden rule:** Source tables (`subscriptions_v2`, `invoices_v2`, `payment_intents_v2`, `ledger_entries`, `ledger_lines`) are append-only or immutable-after-close. If they are intact, all secondary data can be fully rebuilt from them.

---

## When to Use This Playbook

| Symptom | What to rebuild |
|---|---|
| Dashboard shows wrong counts or zero values | Projection tables |
| Ledger balance screen shows wrong totals | Ledger snapshots |
| Revenue recognition not appearing in reports | Revenue recognition schedules |
| Notification consumers not receiving events | Outbox backlog |
| DLQ needs full re-feed | Outbox DLQ → replay |
| Subscription projections entirely missing | Full projection rebuild |

---

## 1. Rebuild Read Projections

**Trigger via API:**
```
POST /ops/projections/rebuild?name=subscription_status
POST /ops/projections/rebuild?name=invoice_summary
POST /ops/projections/rebuild?name=payment_summary
```

**What each does internally:**

```
subscription_status:
  SELECT * FROM subscriptions_v2 (all active/past-due)
  → upsert into subscription_projections
  → fields: merchant_id, plan_id, status, period, next_billing_date, version

invoice_summary:
  SELECT status, COUNT(*), SUM(amount) FROM invoices_v2 GROUP BY merchant_id, status
  → upsert into invoice_projection_summaries

payment_summary:
  SELECT gateway, status, SUM(captured_amount), COUNT(*) FROM payment_intents_v2
  GROUP BY merchant_id, gateway
  → upsert into payment_projection_summaries
```

**Verify rebuild completed:**
```sql
-- Check last_rebuilt_at on the projection metadata table
SELECT projection_name, last_rebuilt_at, row_count
FROM projection_metadata;
```

---

## 2. Rebuild Ledger Snapshots

Ledger snapshots materialize account balances as of a specific date. They are used by reporting APIs to avoid scanning the full `ledger_lines` table on every request.

**API:**
```
POST /ops/ledger/snapshots/rebuild?date=YYYY-MM-DD
Authorization: Bearer {adminToken}
```

**Internal logic (`LedgerSnapshotServiceImpl.materializeSnapshotForDate`):**
```
1. Lock: acquire job lock 'ledger_snapshot_{date}'
2. Delete existing snapshot rows for this date
3. For each active LedgerAccount:
   SELECT SUM(amount) WHERE side='DEBIT' - SUM(amount) WHERE side='CREDIT'
   FROM ledger_lines WHERE entry.posted_at <= {date}
4. Insert snapshot row: {account_id, date, balance}
5. Release lock
```

**Verify:**
```sql
SELECT la.code, ls.balance, ls.snapshot_date
FROM ledger_snapshots ls
JOIN ledger_accounts la ON ls.account_id = la.id
WHERE ls.snapshot_date = :date
ORDER BY la.code;
```

**Cross-check via live scan (should match snapshot):**
```sql
SELECT la.code,
  SUM(CASE WHEN ll.side='DEBIT' THEN ll.amount ELSE -ll.amount END) AS live_balance
FROM ledger_lines ll
JOIN ledger_entries le ON ll.entry_id = le.id
JOIN ledger_accounts la ON ll.account_id = la.id
WHERE le.posted_at::date <= :date
GROUP BY la.code;
```

---

## 3. Regenerate Revenue Recognition Schedules

Revenue recognition schedules are generated when an invoice is finalized. If an invoice was finalized but the schedule is missing (e.g., due to a job crash mid-batch), you can regenerate only the orphaned invoices.

**Find orphaned invoices (finalized but no schedule):**
```sql
SELECT iv.id, iv.merchant_id, iv.plan_id, iv.amount, iv.finalized_at
FROM invoices_v2 iv
WHERE iv.status = 'PAID'
  AND NOT EXISTS (
    SELECT 1 FROM revenue_recognition_schedules rrs
    WHERE rrs.invoice_id = iv.id
  )
ORDER BY iv.finalized_at DESC;
```

**Regenerate via admin API:**
```
POST /api/v1/admin/revenue-recognition/generate-missing
Authorization: Bearer {adminToken}
Content-Type: application/json

{
  "invoiceIds": ["uuid1", "uuid2"]
}
```

**What happens:**
- For each invoice: daily recognition rows generated at `amount / period_days`
- Last row absorbs rounding remainder
- Status set to `PENDING`
- Nightly job will pick up and post them

**Verify:**
```sql
SELECT COUNT(*), MIN(recognition_date), MAX(recognition_date), status
FROM revenue_recognition_schedules
WHERE invoice_id = :invoiceId
GROUP BY status;
```

---

## 4. Clean Up Stuck Outbox Events

**Scenario A: Events stuck in PENDING (poller not picking up)**

Check:
```sql
SELECT COUNT(*), MIN(created_at), MAX(created_at)
FROM outbox_events
WHERE status = 'PENDING'
  AND created_at < NOW() - INTERVAL '5 minutes';
```

If 0 rows → poller is working. If large count → poller is stuck or off.

Fix: Restart application (re-binds the `@Scheduled` poller). Check for stuck job lock:
```sql
SELECT * FROM job_locks WHERE name = 'outbox_poller';
```

**Scenario B: Events stuck in PROCESSING (poller died mid-batch)**

These will NOT be picked up again automatically because status is `PROCESSING`.

Reset them:
```sql
-- Reset PROCESSING events that have been stuck > 10 minutes
UPDATE outbox_events
SET status = 'PENDING', processing_node = NULL
WHERE status = 'PROCESSING'
  AND updated_at < NOW() - INTERVAL '10 minutes';
```

Then restart the application to let the poller pick them up.

**Scenario C: Events in DLQ (FAILED)**

See [02-dlq-retry-runbook.md](02-dlq-retry-runbook.md) for the full replay procedure.

Quick bulk replay:
```
POST /ops/outbox/dlq/replay
Authorization: Bearer {adminToken}
```

---

## 5. Rebuild From Domain Events Log

If a projection or derived table is completely missing and you need to rebuild it event by event:

**View the domain events log:**
```sql
SELECT event_type, event_source_id, merchant_id, payload, created_at
FROM domain_events
WHERE merchant_id = :merchantId
ORDER BY created_at;
```

**Common rebuild scenarios using domain_events:**

| Need to rebuild | Key event_type to use | Target table |
|---|---|---|
| Subscription lifecycle | `SUBSCRIPTION_CREATED`, `SUBSCRIPTION_ACTIVATED`, `SUBSCRIPTION_CANCELLED` | `subscriptions_v2` status timeline |
| Invoice history | `INVOICE_CREATED`, `INVOICE_FINALIZED`, `INVOICE_PAID`, `INVOICE_VOID` | `invoices_v2` status history |
| Payment history | `PAYMENT_INTENT_CREATED`, `PAYMENT_CAPTURED`, `PAYMENT_FAILED` | `payment_intents_v2` log |
| Ledger audit trail | `LEDGER_ENTRY_POSTED` | `ledger_entries` cross-check |

**Note:** Domain events are stored and never deleted. They are the reliable source for any projection rebuild or audit.

---

## 6. Full Database Restore and Verification

If you are restoring from a backup (e.g., DB corruption, accidental bulk delete):

1. Restore from the most recent backup to a stage environment first; verify row counts match expectations.
2. Identify the point-in-time gap: `NOW() - backup_timestamp`.
3. For that gap, any events in the outbox that were emitted but not yet persisted to downstream may be re-emitted on restart (idempotency guards prevent duplicates).
4. Run reconciliation for the gap period manually.
5. Rebuild all projections.
6. Rebuild ledger snapshots for any dates in the gap.
7. Cross-check ledger trial balance against pre-restore values.

---

## Rebuild Safety Summary

| Data type | Safe to delete + rebuild? | Source |
|---|---|---|
| Projection tables | Yes | `subscriptions_v2`, `invoices_v2`, `payment_intents_v2` |
| Ledger snapshots | Yes | `ledger_lines` |
| Revenue schedules (PENDING, no ledger entry yet) | Yes | `invoices_v2` |
| Outbox PENDING/PROCESSING | Yes (reset to PENDING, let poller retry) | `outbox_events` |
| Ledger entries / lines | **NO** — immutable financial record | — |
| Payment records | **NO** — immutable after completion | — |
| Subscription core records | **NO** — state machine source of truth | — |

---

## 7. Run Integrity Engine to Verify Rebuild

After any rebuild, run the Unified Invariant Engine to confirm the rebuilt data satisfies all registered invariants.

### Trigger a full integrity check

```
POST /api/v2/admin/integrity/check
Authorization: Bearer <admin-token>
Content-Type: application/json

{
  "merchantId": null,
  "initiatedByUserId": <admin-user-id>
}
```

### Scope to a single merchant

```json
{ "merchantId": 42, "initiatedByUserId": 1 }
```

### Run a single invariant checker

```
POST /api/v2/admin/integrity/check/billing.invoice_total_equals_line_sum
```

### Interpret the response

```json
{
  "id": 17,
  "status": "COMPLETED",
  "totalChecks": 18,
  "failedChecks": 0,
  "summaryJson": "[{\"key\":\"billing.invoice_total_equals_line_sum\",\"status\":\"PASS\",...}]"
}
```

| `status` | Meaning |
|---|---|
| `COMPLETED` | All checkers passed — rebuild is clean |
| `PARTIAL_FAILURE` | One or more checkers failed — inspect findings |

### Inspect findings after a failure

```
GET /api/v2/admin/integrity/runs/{runId}
```

Each finding includes:
- `invariantKey` — which invariant failed
- `violationCount` — number of violating entities
- `detailsJson` — entity IDs and details
- `suggestedRepairKey` — repair action reference (see `05-manual-repair-actions.md`)

### Registered invariant keys

| Key | Severity | Domain |
|---|---|---|
| `billing.invoice_total_equals_line_sum` | CRITICAL | Billing |
| `billing.discount_total_consistent` | HIGH | Billing |
| `billing.credit_not_over_applied` | CRITICAL | Billing |
| `billing.terminal_invoice_immutability` | CRITICAL | Billing |
| `billing.no_overlapping_invoice_periods` | HIGH | Billing |
| `payments.refund_within_refundable_amount` | CRITICAL | Payments |
| `payments.one_success_effect_per_payment_intent` | CRITICAL | Payments |
| `payments.attempt_number_monotonic` | HIGH | Payments |
| `payments.terminal_intent_no_active_attempts` | HIGH | Payments |
| `ledger.entry_balanced` | CRITICAL | Ledger |
| `ledger.revenue_recognition_within_ceiling` | CRITICAL | Ledger |
| `ledger.no_duplicate_journal_entry` | CRITICAL | Ledger |
| `ledger.settlement_has_matching_journal` | HIGH | Ledger |
| `revenue.schedule_total_equals_invoice_amount` | HIGH | Revenue |
| `revenue.no_duplicate_posting` | CRITICAL | Revenue |
| `revenue.posted_schedule_has_ledger_link` | CRITICAL | Revenue |
| `recon.rerun_idempotency` | MEDIUM | Recon |
| `recon.batch_uniqueness` | HIGH | Recon |
| `recon.payment_in_at_most_one_batch` | HIGH | Recon |
| `events.metadata_populated` | MEDIUM | Events |
| `events.causation_correlation_integrity` | LOW | Events |

---

## Phase 8: API-Driven Rebuild via Safe Repair Actions

Phase 8 replaces ad-hoc rebuild scripts with the **Safe Repair Actions Framework** — a controlled set of idempotent, audited, dry-run-capable operations accessible through the admin API.

All rebuild actions require `ADMIN` role. Every execution is recorded in `repair_actions_audit`.

---

### 8.1 Rebuild a Customer Billing or Merchant KPI Projection

Use `repair.projection.rebuild` for `customer_billing_summary` or `merchant_daily_kpi`.

**Dry-run first:**
```
POST /api/v2/admin/repair/projection/customer_billing_summary/rebuild?dryRun=true
Authorization: Bearer <admin-token>
Content-Type: application/json

{
  "reason": "TICKET-1234: customer counts out of sync after subscription migration",
  "actorUserId": 1,
  "params": {}
}
```

Review the dry-run response, then execute:
```
POST /api/v2/admin/repair/projection/customer_billing_summary/rebuild?dryRun=false
```

Same process for `merchant_daily_kpi`.

**Verify after rebuild:**
```sql
SELECT projection_name, last_rebuilt_at, row_count
FROM projection_metadata
WHERE projection_name IN ('customer_billing_summary', 'merchant_daily_kpi');
```

---

### 8.2 Rebuild Ledger Snapshots

Use `repair.ledger.rebuild_snapshot` when a snapshot for a specific date is missing or corrupt.

Unlike projections, this action does **not** support dry-run — it will delete and recreate the snapshot rows immediately.

```
POST /api/v2/admin/repair/ledger-snapshot/run?date=2025-01-15
Authorization: Bearer <admin-token>
Content-Type: application/json

{
  "reason": "TICKET-2345: snapshot missing for 2025-01-15 after DB restore",
  "actorUserId": 1,
  "params": {}
}
```

Required parameter: `date` (ISO-8601, YYYY-MM-DD).

**Verify:**
```sql
SELECT la.code, ls.balance, ls.snapshot_date
FROM ledger_snapshots ls
JOIN ledger_accounts la ON ls.account_id = la.id
WHERE ls.snapshot_date = '2025-01-15'
ORDER BY la.code;
```

**Cross-check against live calculation:**
```sql
SELECT la.code,
  SUM(CASE WHEN ll.side = 'DEBIT' THEN ll.amount ELSE -ll.amount END) AS live_balance
FROM ledger_lines ll
JOIN ledger_entries le ON ll.entry_id = le.id
JOIN ledger_accounts la ON ll.account_id = la.id
WHERE le.posted_at::date <= '2025-01-15'
GROUP BY la.code;
```

---

### 8.3 Regenerate Revenue Recognition Schedule for a Paid Invoice

Use `repair.revenue.regenerate_schedule` after a PAID invoice is found to be missing its recognition schedule (e.g., batch crash mid-generation, migration rollback).

**Safety guards enforced by the action:**
- Invoice must be in `PAID` status
- Invoice must have a non-null `subscriptionId`
- Invoice must have non-null `periodStart` and `periodEnd`

**Dry-run to confirm eligibility:**
```
POST /api/v2/admin/repair/revenue-recognition/1001/regenerate?dryRun=true
Authorization: Bearer <admin-token>
Content-Type: application/json

{
  "reason": "TICKET-3456: schedule missing after Phase 7 migration",
  "actorUserId": 1,
  "params": {}
}
```

If `success=true` in dry-run, execute:
```
POST /api/v2/admin/repair/revenue-recognition/1001/regenerate?dryRun=false
```

The underlying service (`RevenueRecognitionScheduleService.generateScheduleForInvoice`) is **idempotent** — existing schedule rows are replaced, not duplicated.

**Bulk repair for orphaned invoices — find all:**
```sql
SELECT iv.id
FROM invoices_v2 iv
WHERE iv.status = 'PAID'
  AND iv.subscription_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM revenue_recognition_schedules rrs
    WHERE rrs.invoice_id = iv.id
  );
```

Then call the repair endpoint for each `id`.

---

### 8.4 Retry Stuck Outbox Events

Use `repair.outbox.retry_event` to reset a single FAILED outbox event back to NEW so the poller retries it.

```
POST /api/v2/admin/repair/outbox/{outboxEventId}/retry
Authorization: Bearer <admin-token>
Content-Type: application/json

{
  "reason": "Event failed after downstream timeout; downstream is now recovered",
  "actorUserId": 1,
  "params": {}
}
```

For bulk FAILED events, use the SQL bulk reset first (see `05-manual-repair-actions.md: §6`), then use the API for individual events that need tracking.

---

### 8.5 Retry a Failed Webhook Delivery

Use `repair.webhook.retry_delivery` to reset a GAVE_UP or FAILED webhook delivery back to PENDING.

```
POST /api/v2/admin/repair/webhook-delivery/{deliveryId}/retry
Authorization: Bearer <admin-token>
Content-Type: application/json

{
  "reason": "Merchant endpoint was returning 503 for 2h; now healthy",
  "actorUserId": 1,
  "params": {}
}
```

---

### 8.6 Re-run Reconciliation for a Past Date

Use `repair.recon.run` to trigger the reconciliation job for a date it missed.

```
POST /api/v2/admin/repair/recon/run?date=2025-01-15
Authorization: Bearer <admin-token>
Content-Type: application/json

{
  "reason": "TICKET-4567: nightly recon job skipped 2025-01-15 due to scheduler outage",
  "actorUserId": 1,
  "params": {}
}
```

After completion, verify:
```sql
SELECT id, recon_date, total_transactions, mismatch_count, status
FROM reconciliation_reports
WHERE recon_date = '2025-01-15'
ORDER BY created_at DESC
LIMIT 5;
```

---

### 8.7 Audit Trail for All Repair Operations

Every API-driven repair writes to `repair_actions_audit`. Review before and after any rebuild sequence:

```sql
-- Most recent 50 repair operations
SELECT id, repair_key, target_type, target_id, actor_user_id,
       status, dry_run, reason, created_at
FROM repair_actions_audit
ORDER BY created_at DESC
LIMIT 50;
```

```sql
-- All operations on a specific invoice
SELECT * FROM repair_actions_audit
WHERE target_type = 'invoice' AND target_id = '1001'
ORDER BY created_at;
```

Also available via API:
```
GET /api/v2/admin/repair/audit?page=0&size=50
Authorization: Bearer <admin-token>
```

---

### 8.8 Post-Rebuild Verification Sequence

After any Phase 8 rebuild, re-run the Unified Invariant Engine to confirm all registered invariants pass:

```
POST /api/v2/admin/integrity/check
Authorization: Bearer <admin-token>
Content-Type: application/json

{
  "merchantId": null,
  "initiatedByUserId": 1
}
```

A `"status": "COMPLETED"` with `"failedChecks": 0` confirms the rebuild is clean.

For merchant-scoped verification:
```json
{ "merchantId": 42, "initiatedByUserId": 1 }
```

See `06-data-rebuild-playbook.md §7` for the full invariant key table and `05-manual-repair-actions.md §Phase 8` for request/response reference.


---

## Phase 20 — Post-Rebuild Verification Using New Admin Endpoints

### Step 1: Check Deep Health After Rebuild

```
GET /api/v2/admin/system/health/deep
```

Expected after a successful full projection rebuild:
- `overallStatus: HEALTHY`
- `integrityViolationCount: 0`
- `reconMismatchOpenCount: 0`
- `integrityLastRunStatus: COMPLETED`

### Step 2: Check System Summary

```
GET /api/v2/admin/system/summary
```

Verify `staleJobLockCount: 0` — a non-zero value means a scheduler process crashed mid-rebuild
and the lock must be manually released before the next run.

### Step 3: Confirm Scaling Readiness Is Unchanged

```
GET /api/v2/admin/system/scaling-readiness
```

Confirm `architectureShape: MODULAR_MONOLITH` and `stage_1` is CURRENT.
This endpoint is a canary for unexpected service decomposition side effects.

### Interpreting `integrityLastRunStatus`

| Value | Meaning | Action |
|-------|---------|--------|
| `COMPLETED` | Last integrity check passed | None |
| `FAILED` | Last integrity check threw an unrecoverable error | Re-run via manual trigger |
| `NONE` | No integrity check has ever run | Verify scheduler registration |
