# Manual Repair Actions

This document catalogs every repair action that can be taken by an operator or engineer to fix broken state in production. Each action includes: when to use it, how to invoke it, safety preconditions, and audit requirement.

**Rule:** All repair actions must be logged (who, when, why, affected IDs) before execution. Financial repair actions require two-engineer review.

---

## Action Registry

| Action | Severity | Reversible | Requires Review |
|---|---|---|---|
| Release stuck job lock | P1/P2 | Yes | No |
| Force-cancel a subscription | P1 | Yes (with effort) | Yes |
| Void an invoice | P1 | No | Yes |
| Mark recon mismatch RESOLVED | P2 | Yes | No |
| Mark recon mismatch IGNORED | P2 | Yes | No |
| Replay DLQ event | P2 | Yes | No |
| Post a reversing ledger entry | P0 | No (double-entry) | Yes |
| Trigger projection rebuild | P2 | Yes | No |
| Remove an IP block | P2 | Yes | No |
| Reset idempotency key (expiry) | P2 | No | No |
| Disable/re-enable webhook endpoint | P3 | Yes | No |

---

## 1. Release a Stuck Job Lock

**When:** A scheduled job (dunning, reconciliation, revenue recognition, outbox cleanup) crashed without releasing its lock. The next run is blocked.

**Symptom:** Job has not run in > 2× its interval and `job_locks.locked_by` is non-null with an old timestamp.

**Check first:**
```sql
SELECT name, locked_by, locked_at FROM job_locks;
```

**Release:**
```sql
-- Confirm the job is truly not running (check application logs)
UPDATE job_locks
SET locked_by = NULL, locked_at = NULL
WHERE name = :job_name
  AND locked_at < NOW() - INTERVAL '30 minutes';
```

**Audit:** Log `OPERATOR:{name} released job lock {job_name} at {timestamp}` in your incident log.

---

## 2. Force-Cancel a Subscription

**When:** A subscription is stuck in `ACTIVE` or `PAST_DUE` and all normal cancellation paths are blocked (customer-facing endpoint returned an error, dunning failed).

**API:**
```
POST /api/v2/subscriptions/{subscriptionId}/cancel
Authorization: Bearer {adminToken}
Content-Type: application/json

{
  "reasonCode": "ADMIN_FORCE_CANCEL",
  "cancelledBy": "operator@firstclub.com",
  "memo": "Incident reference: INC-{id}"
}
```

**What happens:**
- `subscriptions_v2.status` → `CANCELLED`
- `cancelled_at` timestamp set
- `SUBSCRIPTION_CANCELLED` event fired
- Outbox entry written

**Preconditions:**
- Confirm no active dunning attempt in `IN_PROGRESS` status for this subscription
- Confirm no open payment intent for this subscription

**Audit:** Required. Note in incident log with `subscriptionId`, operator email, reason.

---

## 3. Void an Invoice

**When:** An invoice was generated incorrectly (wrong amount, wrong merchant, orphaned) and must not be collected.

**API:**
```
POST /api/v1/invoices/{invoiceId}/void
Authorization: Bearer {adminToken}
Content-Type: application/json

{
  "reason": "ADMIN_CORRECTION",
  "memo": "Incident reference: INC-{id}"
}
```

**What happens:**
- `invoices_v2.status` → `VOID`
- Prevents further dunning or payment attempts against this invoice
- Does NOT reverse any ledger entries (if payment was already captured, use ledger reversal)

**Preconditions:**
- Invoice must be in `ISSUED` or `PAST_DUE` status
- Must NOT already have a `COMPLETED` payment intent

**Audit:** Required. Coordinator review before execution.

**Warning:** Irreversible. A voided invoice cannot be reinstated.

---

## 4. Resolve or Ignore a Reconciliation Mismatch

**When:** A mismatch has been investigated and either corrected upstream or accepted as within tolerance.

**Mark RESOLVED:**
```
PATCH /api/v1/admin/recon/mismatches/{mismatchId}/resolve
Authorization: Bearer {adminToken}
Content-Type: application/json

{
  "resolution_note": "Payment confirmed with gateway; record updated. Incident: INC-{id}"
}
```

**Mark IGNORED:**
```
PATCH /api/v1/admin/recon/mismatches/{mismatchId}/ignore
Authorization: Bearer {adminToken}
Content-Type: application/json

{
  "ignore_reason": "Known duplicate import; see JIRA-{ticket}"
}
```

**Note:** Records are never deleted. Status moves OPEN → RESOLVED or IGNORED. Either can be reverted by reopening.

---

## 5. Replay a DLQ Event

**When:** An outbox event failed to process and is in the dead letter queue.

**Single event:**
```
POST /ops/outbox/dlq/{eventId}/replay
Authorization: Bearer {adminToken}
```

**Bulk by type:**
```
POST /ops/outbox/dlq/replay?eventType=SUBSCRIPTION_ACTIVATED
Authorization: Bearer {adminToken}
```

**Bulk by merchant:**
```
POST /ops/outbox/dlq/replay?merchantId={merchantId}
Authorization: Bearer {adminToken}
```

**Preconditions:**
- Confirm root cause of original failure is fixed
- Review event payload before replay (deserialization check)
- For financial events (`PAYMENT_CAPTURED`, `REFUND_ISSUED`): verify no ledger entry already exists for the reference_id to prevent duplicates

**Replay safety:** Consumers are idempotent. Replaying the same event twice is safe; the second processing returns a no-op.

---

## 6. Post a Reversing Ledger Entry

**When:** A ledger entry was posted incorrectly (wrong account, wrong amount, wrong direction). Double-entry system is append-only; corrections are made via mirror-image reversals.

**Requires:** Two-engineer review. Must state the original ledger entry ID being reversed.

**Steps:**
1. Identify the original entry:
```sql
SELECT le.id, le.entry_type, le.reference_id, le.description, le.created_at,
       ll.account_id, la.code, ll.side, ll.amount
FROM ledger_entries le
JOIN ledger_lines ll ON ll.entry_id = le.id
JOIN ledger_accounts la ON ll.account_id = la.id
WHERE le.reference_id = :referenceId;
```

2. Construct reversing entry (swap debit ↔ credit, same accounts, same amounts, entry_type = `ADMIN_REVERSAL`, description references original entry ID).

3. Post via admin API:
```
POST /api/v1/admin/ledger/reverse
Authorization: Bearer {adminToken}
Content-Type: application/json

{
  "originalEntryId": "uuid",
  "reason": "Incorrect account in original. Incident: INC-{id}",
  "correctedBy": "engineer@firstclub.com"
}
```

**Note:** The system auto-generates the mirror journal if the endpoint receives the originalEntryId. Do not post the reversing lines manually via SQL.

**Audit:** Mandatory. Finance lead sign-off required.

---

## 7. Trigger a Projection Rebuild

**When:** A read projection (subscription status, invoice summary, payment summary) is showing stale or incorrect data.

**API:**
```
POST /ops/projections/rebuild?name={projectionName}
Authorization: Bearer {adminToken}
```

**Available projection names:**

| Name | Source | What it rebuilds |
|---|---|---|
| `subscription_status` | `subscriptions_v2` | Current status, plan, merchant summaries |
| `invoice_summary` | `invoices_v2` | Open/paid/overdue invoice counts per merchant |
| `payment_summary` | `payment_intents_v2` | Capture rates, refund totals per merchant |
| `ledger_snapshot` | `ledger_lines` | Account balances as of a given date |

**Notes:**
- Projection rebuild is non-destructive; existing projection rows are replaced, not deleted
- Financial source tables are not affected
- Rebuilds run synchronously (may take > 10 seconds for large datasets)

---

## 8. Remove an IP Block

**When:** An IP address was incorrectly blocked, or a legitimate merchant's NAT IP was flagged.

**Check current blocks:**
```
GET /api/v1/risk/ip-blocks
Authorization: Bearer {adminToken}
```

**Remove:**
```
DELETE /api/v1/risk/ip-blocks/{ipAddress}
Authorization: Bearer {adminToken}
```

**Audit:** Required. Note which merchant and what triggered the original block.

---

## 9. Disable / Re-enable a Webhook Endpoint

**Disable (to stop delivery storm):**
```
PATCH /api/v1/merchants/{merchantId}/webhooks/{endpointId}/disable
Authorization: Bearer {adminToken}
```

**Re-enable:**
```
PATCH /api/v1/merchants/{merchantId}/webhooks/{endpointId}/enable
Authorization: Bearer {adminToken}
```

**Note:** When re-enabled, the system will attempt to redeliver any events that piled up in the DLQ during the disabled period (up to `max_retry_attempts`).

---

## Safety Checklist: Before Any Manual Action

- [ ] Incident or ticket ID noted
- [ ] Affected entity IDs documented
- [ ] Current state confirmed (not assumed) via direct DB query
- [ ] Expected outcome after action defined
- [ ] Verification query planned to confirm success
- [ ] Two-engineer review obtained for financial actions
- [ ] Communication sent if merchant-visible impact expected

---

## Phase 8: Programmatic Repair Actions Framework

Phase 8 introduces a structured, API-driven repair framework that replaces ad-hoc SQL scripts with versioned, audited, dry-run-capable repair actions. Every action is registered in `RepairActionRegistry`, executes under a controlled context, and writes an immutable row to `repair_actions_audit`.

### Repair Action Inventory

| Repair Key | Target Type | Dry-run | Safety Guards | Mutates Immutable Records? |
|---|---|---|---|---|
| `repair.invoice.recompute_totals` | `invoice` | Yes | Invoice must exist | No |
| `repair.projection.rebuild` | `projection` | Yes | Name must be `customer_billing_summary` or `merchant_daily_kpi` | No |
| `repair.ledger.rebuild_snapshot` | `ledger_snapshot` | No | `date` param required; ISO-8601 | No |
| `repair.outbox.retry_event` | `outbox_event` | No | OutboxEvent must exist; resets to NEW only | No |
| `repair.webhook.retry_delivery` | `webhook_delivery` | No | Delivery must exist | No |
| `repair.recon.run` | `reconciliation` | No | `date` param required; ISO-8601 | No |
| `repair.revenue.regenerate_schedule` | `invoice` | Yes | Invoice must be PAID + have subscriptionId + have period boundaries | No |

All actions are guarded from mutating ledger entries, payment records, or subscription core state.

---

### API Reference

**Base path:** `/api/v2/admin/repair`  
**Authentication:** `Authorization: Bearer <admin-token>`  
**Authorization:** Role `ADMIN` required on all endpoints.

#### Recompute Invoice Totals
```
POST /api/v2/admin/repair/invoice/{invoiceId}/recompute?dryRun=false
Content-Type: application/json

{
  "reason": "Discount applied post-creation left total inconsistent",
  "actorUserId": 1,
  "params": {}
}
```

#### Rebuild Projection
```
POST /api/v2/admin/repair/projection/{projectionName}/rebuild?dryRun=false
```
Supported projection names: `customer_billing_summary`, `merchant_daily_kpi`

#### Rebuild Ledger Snapshot
```
POST /api/v2/admin/repair/ledger-snapshot/run?date=2025-01-15
Content-Type: application/json

{
  "reason": "Snapshot was missing after server crash on 2025-01-15",
  "actorUserId": 1,
  "params": {}
}
```

#### Retry Outbox Event
```
POST /api/v2/admin/repair/outbox/{outboxEventId}/retry
Content-Type: application/json

{
  "reason": "Event got stuck in FAILED after downstream timeout",
  "actorUserId": 1,
  "params": {}
}
```

#### Retry Webhook Delivery
```
POST /api/v2/admin/repair/webhook-delivery/{deliveryId}/retry
Content-Type: application/json

{
  "reason": "Merchant endpoint was temporarily down",
  "actorUserId": 1,
  "params": {}
}
```

#### Run Reconciliation for a Date
```
POST /api/v2/admin/repair/recon/run?date=2025-01-15
Content-Type: application/json

{
  "reason": "Nightly recon job missed this date due to scheduler restart",
  "actorUserId": 1,
  "params": {}
}
```

#### Regenerate Revenue Recognition Schedule
```
POST /api/v2/admin/repair/revenue-recognition/{invoiceId}/regenerate?dryRun=false
Content-Type: application/json

{
  "reason": "Schedule was deleted during migration rollback; invoice is PAID",
  "actorUserId": 1,
  "params": {}
}
```

#### List Repair Audit Log
```
GET /api/v2/admin/repair/audit?page=0&size=50
```
Returns all audit rows ordered by `created_at DESC`.

---

### Response Format

**Success (200 OK):**
```json
{
  "repairKey": "repair.invoice.recompute_totals",
  "success": true,
  "dryRun": false,
  "auditId": 42,
  "details": "Invoice 1001 recomputed: total updated from 99.00 to 120.00",
  "evaluatedAt": "2025-01-15T10:22:00Z"
}
```

**Dry-run (200 OK, no mutation):**
```json
{
  "repairKey": "repair.revenue.regenerate_schedule",
  "success": true,
  "dryRun": true,
  "auditId": 43,
  "details": "[DRY-RUN] Would regenerate revenue schedule for invoice 1001 (PAID, subscriptionId=7)",
  "beforeSnapshotJson": "{\"existing_schedule_count\": 30}",
  "evaluatedAt": "2025-01-15T10:22:01Z"
}
```

**Failure (200 OK with success=false — error captured to audit):**
```json
{
  "repairKey": "repair.invoice.recompute_totals",
  "success": false,
  "dryRun": false,
  "auditId": 44,
  "errorMessage": "Invoice not found: 9999",
  "evaluatedAt": "2025-01-15T10:22:02Z"
}
```

---

### Audit Trail

Every repair action execution writes a row to `repair_actions_audit`:

```sql
SELECT id, repair_key, target_type, target_id, actor_user_id,
       status, dry_run, reason, created_at
FROM repair_actions_audit
ORDER BY created_at DESC
LIMIT 20;
```

Filter by target:
```sql
SELECT * FROM repair_actions_audit
WHERE target_type = 'invoice' AND target_id = '1001'
ORDER BY created_at DESC;
```

Filter by actor:
```sql
SELECT * FROM repair_actions_audit
WHERE actor_user_id = '1'
ORDER BY created_at DESC;
```

Audit rows are **written in an independent transaction** (`REQUIRES_NEW`). An action that throws will still produce an audit row with `status = 'FAILED'`.

---

### Dry-Run Protocol

For actions that support `dryRun=true`:

1. Pass `?dryRun=true` as a query parameter, **or** set `"dryRun": true` in the request body.
2. The action will evaluate preconditions and snapshot current state.
3. No database mutations are performed.
4. The response includes `beforeSnapshotJson` showing what would have changed.
5. An audit row is written (with `dry_run = true`) so the evaluation is traceable.

**Recommended workflow for financial repairs:**
```
# Step 1: dry-run to verify preconditions are met
POST /api/v2/admin/repair/invoice/1001/recompute?dryRun=true

# Step 2: review the beforeSnapshotJson and details in the response

# Step 3: if satisfied, execute for real
POST /api/v2/admin/repair/invoice/1001/recompute?dryRun=false
```

---

### Safety Checklist for Programmatic Repair Actions

- [ ] Action key and target ID confirmed correct
- [ ] Dry-run executed first (for dry-run-capable actions)
- [ ] Dry-run `details` and `beforeSnapshotJson` reviewed
- [ ] `reason` field populated with incident/ticket reference
- [ ] `actorUserId` set to your admin user ID
- [ ] Two-engineer verification for financial repairs (`repair.invoice.*`, `repair.revenue.*`)

---

## Support Case Integration for Repair Actions (Phase 18)

All significant repair actions — especially financial ones — **must** be preceded
by opening a support case.  This creates an auditable link between the problem
investigation and the corrective action.

### Recommended repair workflow

```bash
# Step 1: Open a support case linked to the affected entity
CASE=$(curl -s -X POST "$BASE/api/v2/admin/support/cases" \
  -H "Authorization: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "merchantId":       1,
    "linkedEntityType": "INVOICE",
    "linkedEntityId":   1001,
    "title":            "Invoice 1001 recompute after billing engine bug",
    "priority":         "HIGH"
  }' | jq -r '.id')

# Step 2: Assign the case to yourself
curl -s -X POST "$BASE/api/v2/admin/support/cases/$CASE/assign" \
  -H "Authorization: $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"ownerUserId\": $MY_USER_ID}"

# Step 3: Dry-run the repair
curl -s -X POST "$BASE/api/v2/admin/repair/invoice/1001/recompute?dryRun=true" \
  -H "Authorization: $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"reason\": \"Case $CASE: billing engine tax rounding bug\", \"actorUserId\": $MY_USER_ID}"

# Step 4: Review and execute real repair
curl -s -X POST "$BASE/api/v2/admin/repair/invoice/1001/recompute?dryRun=false" \
  -H "Authorization: $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"reason\": \"Case $CASE: billing engine tax rounding bug\", \"actorUserId\": $MY_USER_ID}"

# Step 5: Record outcome in a note then close
curl -s -X POST "$BASE/api/v2/admin/support/cases/$CASE/notes" \
  -H "Authorization: $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"noteText\": \"Repair executed. Invoice recomputed. New total: 2450. No further action needed.\", \"authorUserId\": $MY_USER_ID}"

curl -s -X POST "$BASE/api/v2/admin/support/cases/$CASE/close" \
  -H "Authorization: $TOKEN"
```

### Support case entity types for repair scenarios

| Repair action family | Use `linkedEntityType` |
|---|---|
| `repair.invoice.*` | `INVOICE` |
| `repair.revenue.*` | `INVOICE` |
| `repair.recon.*` | `RECON_MISMATCH` |
| Payment / refund issues | `PAYMENT_INTENT` or `REFUND` |
| Chargeback / dispute handling | `DISPUTE` |
| Subscription state repair | `SUBSCRIPTION` |
| Customer data corrections | `CUSTOMER` |

### Cases in the entity timeline

After a repair, `GET /api/v2/admin/timeline/INVOICE/{invoiceId}` will show a
`SUPPORT_CASE_OPENED`, `SUPPORT_CASE_ASSIGNED`, and `SUPPORT_CASE_CLOSED` event
sequence — providing a permanent audit trail without requiring separate log
searches.
