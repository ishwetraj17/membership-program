# Incident Response

This runbook covers the response procedure for the most common production incidents on the FirstClub platform.

---

## Incident Severity Levels

| Severity | Definition | Response Time |
|---|---|---|
| **P0** | Financial data integrity issue; double-charge; missing ledger entries; DB down | Immediate |
| **P1** | Payment processing halted; reconciliation failing; outbox stuck | 30 minutes |
| **P2** | Webhook delivery degraded; dashboard showing stale data; dunning job failure | 2 hours |
| **P3** | Report export failing; non-critical API errors | Next business day |

---

## P0: Financial Data Integrity Issue

**Symptoms:**
- Duplicate `payment_captured` ledger entries for the same payment
- `DISPUTE_RESERVE` account balance is negative
- `refunded_amount > captured_amount` on a payment record
- Recon mismatch of type `PAYMENT_NO_INVOICE` (payment without an invoice)

**Immediate Actions:**

1. **Do NOT make production DB changes without a review.**
2. Alert engineering lead and finance lead immediately.
3. Run the integrity check queries:

```sql
-- Check for duplicate PAYMENT_CAPTURED entries
SELECT reference_id, COUNT(*) AS entry_count
FROM ledger_entries
WHERE entry_type = 'PAYMENT_CAPTURED'
GROUP BY reference_id
HAVING COUNT(*) > 1;

-- Check for over-refunded payments
SELECT id, invoice_id, captured_amount, refunded_amount
FROM payment_intents_v2
WHERE refunded_amount > captured_amount;

-- Check DISPUTE_RESERVE balance
SELECT SUM(CASE WHEN ll.side='DEBIT' THEN ll.amount ELSE -ll.amount END) AS reserve_balance
FROM ledger_lines ll
JOIN ledger_accounts la ON ll.account_id = la.id
WHERE la.code = 'DISPUTE_RESERVE';
```

4. Freeze the affected merchant or payment if risk of further damage exists.
5. Document the exact IDs affected before any remediation.
6. Remediation must be reviewed by at least two engineers.

---

## P0: PostgreSQL Down

**Symptoms:**
- All API endpoints return 500
- Deep health returns `DOWN`
- Application logs show `Connection refused` or `could not connect to server`

**Steps:**

1. Check DB process health via your hosting console or `pg_ctl status`.
2. If DB is recoverable: restart Postgres; verify WAL recovery completes.
3. If DB needs failover: promote the replica; update `application.properties` target host.
4. Restart the Spring Boot application.
5. Verify with:
   ```
   GET /ops/health/deep
   ```
6. Check for any in-flight operations at time of failure using `pg_stat_activity` after recovery.
7. Trigger manual recon for any missed nightly jobs:
   ```
   POST /api/v1/admin/recon/settle?date=YYYY-MM-DD
   ```

---

## P1: Payment Processing Halted

**Symptoms:**
- All `POST /api/v2/payments/intents` returning errors
- `payment_attempts_v2` rows pile up at `status=INITIATED` without progression
- Gateway health endpoint returning failures

**Steps:**

1. Check gateway health:
```sql
SELECT gateway_name, is_healthy, last_checked_at, failure_reason
FROM gateway_health_snapshots
ORDER BY last_checked_at DESC;
```

2. Check routing rules:
```sql
SELECT * FROM routing_rules WHERE is_active = true ORDER BY priority;
```

3. Check deep health for DB and app status:
```
GET /ops/health/deep
```

4. If a specific gateway is down: check if fallback gateway is configured in routing rules.
5. If all gateways are down: this is a complete payment outage. Alert engineering. No user-facing fix is possible until a gateway is restored.
6. Once gateway is restored: dunning engine will retry PAST_DUE subscriptions automatically.

---

## P1: Reconciliation Failure (Consecutive Nights)

**Steps:**

1. Check `recon_batches` for the failed runs:
```sql
SELECT batch_date, status, error_message, started_at, completed_at
FROM recon_batches
ORDER BY batch_date DESC
LIMIT 7;
```

2. Check if `JobLock` is stuck:
```sql
SELECT * FROM job_locks WHERE name = 'reconciliation_daily';
```

3. If lock is stuck (job crashed without releasing):
```sql
-- Verify the previous job is truly not running
UPDATE job_locks SET locked_by = NULL WHERE name = 'reconciliation_daily';
```

4. Re-trigger:
```
POST /api/v1/admin/recon/settle?date=YYYY-MM-DD
```

5. If still failing: review application logs for the exception type. Most common causes: DB query timeout on large tables (add/tune indexes), missing settlement data for Layer 3/4.

---

## P2: Outbox / Event Lag

See: [02-dlq-retry-runbook.md](02-dlq-retry-runbook.md)

**Quick summary:**
1. `GET /api/v2/admin/system/outbox/lag` → check `totalPending`, `staleLeasesCount`, `oldestPendingAgeSeconds`
2. `GET /api/v2/admin/system/dlq/summary` → inspect DLQ by `failureCategory`
3. `GET /api/v2/admin/system/dlq?failureCategory=TRANSIENT_ERROR` → list retryable entries
4. Fix root cause → retry individual entries via `POST /api/v2/admin/system/dlq/{id}/retry`

---

## P1: Outbox Stuck / Stale Processing Leases

**Symptoms:**
- `oldestPendingAgeSeconds > 1800` (30 minutes) in the outbox lag report
- `staleLeasesCount > 0` persisting for more than 5 minutes (stale-lease recovery should auto-reset)
- Application logs show no `OutboxPoller: processing` lines for > 5 minutes
- `dead_letter_messages` count growing rapidly

**Phase 16 automated recovery:**  
The `OutboxPoller.recoverStaleLeases()` task runs every 5 minutes and automatically
resets events stuck in `PROCESSING` for > 5 minutes back to `NEW`.  Check the logs
for `OutboxPoller: stale lease recovery reset N event(s)` to confirm it is running.

**Steps:**

1. Get lag report:
```
GET /api/v2/admin/system/outbox/lag
Authorization: Bearer <admin-token>
```
Note: `staleLeasesCount`, `oldestPendingAgeSeconds`, `totalPending`.

2. Get DLQ summary:
```
GET /api/v2/admin/system/dlq/summary
```
Note the dominant `failureCategory`.

3. Check deep health (DB + Redis):
```
GET /api/v2/admin/system/health/deep
```

4. If `staleLeasesCount > 0` and not recovering automatically:
   - Check that the Spring scheduler is running (look for `recoverStaleLeases` log lines).
   - If the application pod is healthy but the scheduler is not firing, restart the pod.
   - As a last resort, run the manual DB reset:
```sql
UPDATE outbox_events
SET status = 'NEW',
    processing_owner = NULL,
    processing_started_at = NULL,
    next_attempt_at = NOW()
WHERE status = 'PROCESSING'
  AND processing_started_at < NOW() - INTERVAL '10 minutes';
```

5. If `failureCategory = 'TRANSIENT_ERROR'` dominates:
   - Wait for the transient condition to clear (DB / downstream service recovers).
   - Stale events will self-heal via stale-lease recovery.
   - Retry DLQ entries after stable for 5 minutes:
```
GET /api/v2/admin/system/dlq?failureCategory=TRANSIENT_ERROR
POST /api/v2/admin/system/dlq/{id}/retry  ← per entry
```

6. If the outbox poller is simply overloaded (events processing, just slowly):
   - Monitor `totalPending` trend.  If decreasing, wait.
   - If not decreasing: horizontal scaling or reducing poll interval.

7. After resolution: confirm with:
```
GET /api/v2/admin/system/outbox/lag
```
Expect `staleLeasesCount = 0` and `oldestPendingAgeSeconds < 60`.

---

## P2: Webhook Delivery Degraded / Endpoints Auto-Disabled

**Symptoms:**
- Merchant reports they have stopped receiving webhook events
- `merchant_webhook_endpoints` rows with `auto_disabled_at IS NOT NULL`
- `merchant_webhook_deliveries` rows accumulating in `GAVE_UP` state
- Logs show `Auto-disabled webhook endpoint {id} after N consecutive failures`

**Diagnosis:**

```sql
-- Count auto-disabled endpoints
SELECT id, merchant_id, url, consecutive_failures, auto_disabled_at
FROM merchant_webhook_endpoints
WHERE auto_disabled_at IS NOT NULL
ORDER BY auto_disabled_at DESC;

-- Recent GAVE_UP deliveries for an endpoint
SELECT id, event_type, last_response_code, last_error, created_at
FROM merchant_webhook_deliveries
WHERE endpoint_id = :endpointId AND status = 'GAVE_UP'
ORDER BY created_at DESC
LIMIT 20;

-- Check if any delivery is currently in-flight (stuck processingStartedAt)
SELECT id, endpoint_id, processing_owner, processing_started_at
FROM merchant_webhook_deliveries
WHERE processing_started_at IS NOT NULL
  AND processing_started_at < NOW() - INTERVAL '5 minutes';
```

**Actions:**

1. Use the search API to understand the failure pattern:
```
GET /api/v2/merchants/{merchantId}/webhook-deliveries/search?status=GAVE_UP&limit=50
```

2. If the endpoint URL is reachable and the merchant wants to resume, re-enable it:
```
PATCH /api/v2/merchants/{merchantId}/webhook-endpoints/{endpointId}/reenable
```
This resets `active=true`, `autoDisabledAt=null`, `consecutiveFailures=0`.

3. Verify the endpoint is working with a test ping:
```
POST /api/v2/merchants/{merchantId}/webhook-endpoints/{endpointId}/ping
```

4. Retry individual GAVE_UP deliveries via the Admin Repair API (if needed):
```
POST /api/v2/admin/repair/webhook-delivery/{deliveryId}/retry
```
> The repair action will refuse to retry a delivery that is already `DELIVERED` or currently
> in-flight (`processingStartedAt != null`).

5. If the endpoint keeps auto-disabling after re-enable, the merchant's server is unhealthy.
   Advise the merchant to fix their webhook handler before re-enabling further.

---

## P2: Dunning Job Failure

**Symptoms:**
- `subscriptions_v2` rows stuck at `PAST_DUE` for > 7 days
- `dunning_attempts` table shows rows never transitioning past `SCHEDULED`
- Application logs show dunning job errors

**Steps:**

1. Check dunning job lock:
```sql
SELECT * FROM job_locks WHERE name LIKE 'dunning%';
```

2. Check pending dunning attempts:
```sql
SELECT da.id, da.subscription_id, da.attempt_number, da.scheduled_at, da.status
FROM dunning_attempts da
WHERE da.status = 'SCHEDULED'
  AND da.scheduled_at < NOW()
ORDER BY da.scheduled_at
LIMIT 50;
```

3. If job lock is stuck: release it (same pattern as recon).
4. Restart the application to trigger the `@Scheduled` job on next cron tick.
5. Check that the dunning policy for the affected merchant exists:
```sql
SELECT * FROM dunning_policies WHERE merchant_id = :merchantId AND is_active = true;
```

---

## P2: Stale Projections / Dashboard

**Steps:**

1. Identify which projection is stale (subscription status, invoice summary, payment summary).
2. Rebuild:
```
POST /ops/projections/rebuild?name=subscription_status
```

3. Monitor rebuild progress via logs.

4. If rebuild is blocked (rebuild_lock stuck):
```sql
-- Check rebuild lock (Redis future)
-- For now: restart application
```

---

## P3: Rate Limit False-Positive Blocking Legitimate Traffic

**Trigger:** Users or merchants report HTTP 429 responses they should not be receiving.
The `rateLimitBlocksLastHour` counter in the deep-health report is unexpectedly high.

**Triage steps:**

1. Check current policy config:
   ```
   GET /api/v2/admin/system/rate-limits
   Authorization: Bearer <admin-token>
   ```

2. Query recent blocks to understand which policy is firing:
   ```sql
   SELECT category, subject_key, COUNT(*) AS blocks
   FROM rate_limit_events
   WHERE blocked = true AND created_at > NOW() - INTERVAL '30 minutes'
   GROUP BY category, subject_key
   ORDER BY blocks DESC
   LIMIT 20;
   ```

3. Check if Redis is healthy (a Redis blip before recovery could cause burst-on-reconnect):
   ```
   GET /api/v2/admin/system/redis/health
   ```

**Resolution options (least to most invasive):**

| Option | When to use |
|---|---|
| Wait — window expires naturally | Temporary spike; limits are correct |
| Raise `app.rate-limit.policies.<POLICY>.limit` in config | Traffic legitimately increased |
| Widen `app.rate-limit.policies.<POLICY>.window-seconds` | Burst pattern, not sustained volume |
| Set `app.rate-limit.enabled=false` and redeploy | Emergency — complete shutdown of rate limiting |

> **Note:** Setting `app.rate-limit.enabled=false` disables **all** rate limiting globally,
> exposing the system to credential stuffing and webhook storms.
> Use only as a last resort and re-enable as soon as the root cause is resolved.

---

## Post-Incident Checklist

After every P0 or P1 incident:

- [ ] Root cause identified and documented
- [ ] Fix deployed and verified
- [ ] Affected financial records audited (ledger balance, recon)
- [ ] Incident timeline written
- [ ] Monitoring/alerting gaps identified
- [ ] Runbook updated if new patterns discovered
- [ ] Finance team notified if monetary impact existed

---

## Emergency Contacts

| Role | When to Contact |
|---|---|
| Engineering Lead | All P0, P1 incidents |
| Finance Lead | P0 financial integrity, any monetary discrepancy > ₹1,000 |
| Merchant Success | If a specific merchant's payments are halted for > 30 minutes |
| Gateway Support | If gateway API is unreachable for > 15 minutes |

---

## Phase 12: Ops Timeline for Incident Investigation

> Added in Phase 12.  The unified ops timeline (`ops_timeline_events`) is the
> fastest way to understand what happened to any entity during an incident.

### Quick timeline lookups during an incident

```bash
# What happened to a specific subscription during the incident window?
curl "http://localhost:8080/api/v2/admin/timeline/subscription/55?merchantId=3" \
  -H 'Authorization: Bearer <admin-token>'

# What happened to a specific invoice?
curl "http://localhost:8080/api/v2/admin/timeline/invoice/200?merchantId=3" \
  -H 'Authorization: Bearer <admin-token>'

# Generic paginated query with custom sort
curl "http://localhost:8080/api/v2/admin/timeline?merchantId=3&entityType=PAYMENT_INTENT&entityId=300&size=100" \
  -H 'Authorization: Bearer <admin-token>'

# Trace a checkout flow end-to-end by correlation ID
curl "http://localhost:8080/api/v2/admin/timeline/by-correlation/<correlationId>?merchantId=3" \
  -H 'Authorization: Bearer <admin-token>'
```

### Timeline during a P0 payment outage

1. Pull the `paymentIntentId` from the error logs.
2. `GET /api/v2/admin/timeline/payment/{id}` — see all attempts and failure reasons.
3. Check `payloadPreviewJson` on the `PAYMENT_ATTEMPT_FAILED` row for gateway error codes.
4. If multiple customers affected, look for a shared `correlationId` pattern or
   the same `gatewayName` in the summary field.
5. Cross-reference with the `ProjectionAdminController` payment-summary projection
   for PENDING/FAILED counts.

### Timeline during a billing dispute

1. Pull `invoiceId` and `customerId` from the dispute record.
2. `GET /api/v2/admin/timeline/invoice/{invoiceId}` — verify invoice lifecycle.
3. `GET /api/v2/admin/timeline/customer/{customerId}` — check subscription history
   for any `SUBSCRIPTION_SUSPENDED` or `SUBSCRIPTION_CANCELLED` events.
4. `GET /api/v2/admin/timeline/by-correlation/{correlationId}` from the invoice
   row to trace the full billing cycle.

### Timeline dedup guarantee

Timeline rows are deduplicated by `(source_event_id, entity_type, entity_id)`
(unique partial index on `ops_timeline_events`).  Re-running a projection rebuild
or replaying events will not create duplicate timeline entries — safe to replay
during incident recovery.

---

## Phase 13: Unified Admin Search During Incidents

> Added in Phase 13.  When you only have a partial identifier (e.g. a gateway
> transaction ID from a Slack alert, or a customer email from a support ticket),
> use the unified search API to locate the entity in seconds without knowing
> which table to query.

### When to use search vs. timeline

| Situation | Tool |
|---|---|
| "I have an invoice number from the customer" | `/api/v2/admin/search/by-invoice-number/{n}` |
| "I have a gateway reference from the payment provider" | `/api/v2/admin/search/by-gateway-ref/{ref}` |
| "I have a correlation ID from the error log" | `/api/v2/admin/search/by-correlation/{id}` |
| "I know the entity ID and want full history" | `/api/v2/admin/timeline/{entityType}/{id}` |
| "I only have a customer email" | `/api/v2/admin/search?q={email}&merchantId={id}` |

### Quick search recipes

```bash
MERCHANT_ID=3
TOKEN="Bearer <admin-token>"

# Search by any identifier — auto-detects type
curl "http://localhost:8080/api/v2/admin/search?q=INV-2024-001&merchantId=$MERCHANT_ID" \
  -H "Authorization: $TOKEN"

# Find correlated events for an incident trace
curl "http://localhost:8080/api/v2/admin/search/by-correlation/corr-abc-xyz?merchantId=$MERCHANT_ID" \
  -H "Authorization: $TOKEN"

# Look up a Razorpay / Stripe transaction ID
curl "http://localhost:8080/api/v2/admin/search/by-gateway-ref/pay_GW12345?merchantId=$MERCHANT_ID" \
  -H "Authorization: $TOKEN"

# Exact invoice lookup
curl "http://localhost:8080/api/v2/admin/search/by-invoice-number/INV-2024-001234?merchantId=$MERCHANT_ID" \
  -H "Authorization: $TOKEN"

# Customer email lookup (URL-encode the @ sign)
curl "http://localhost:8080/api/v2/admin/search?q=alice%40example.com&merchantId=$MERCHANT_ID" \
  -H "Authorization: $TOKEN"
```

### Typical P1 workflow using search + timeline

1. Grab the error identifier from the alert (gateway ref, correlation ID, or invoice number).
2. `GET /api/v2/admin/search?q=<identifier>&merchantId=<id>` — returns all matching entities.
3. Note the `primaryId` and `resultType` from the result.
4. `GET /api/v2/admin/timeline/{resultType}/{primaryId}` — view the complete event history.
5. If `correlationId` is visible in the timeline row, `GET /by-correlation/{id}` to trace
   the full downstream effect of that single user action.

### Search tenant isolation

The `merchantId` parameter is **required** on every search endpoint.  Results are
filtered at the database level — there is no path through which a search query can
return rows belonging to a different merchant.  Attempting to query without
`merchantId` returns HTTP 400.

### Search result caching

Results are cached in Redis for **30 seconds** (shorter than the 60-second timeline
cache because entity statuses change rapidly during incidents).  Key pattern:

```
{env}:firstclub:search:{merchantId}:{sha256(queryType:queryValue)}
```

The SHA-256 digest ensures sensitive identifiers (emails, correlation IDs) are
not stored as plain text in the Redis key namespace.

---

## Support Case Workflow (Phase 18)

Every P1/P2 investigation should be backed by a **support case** — a persistent,
structured record linked to the specific entity under investigation.  Cases appear
on the linked entity's timeline, giving every engineer a shared view of in-progress
work without duplicating effort.

### Opening a case

```bash
curl -s -X POST "$BASE/api/v2/admin/support/cases" \
  -H "Authorization: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "merchantId":        1,
    "linkedEntityType":  "INVOICE",
    "linkedEntityId":    10042,
    "title":             "Invoice 10042 stuck in PENDING after payment success",
    "priority":          "HIGH"
  }'
```

Valid `linkedEntityType` values: `CUSTOMER`, `SUBSCRIPTION`, `INVOICE`,
`PAYMENT_INTENT`, `REFUND`, `DISPUTE`, `RECON_MISMATCH`.

The service validates that the linked entity exists before creating the case
(HTTP 422 if missing).  A `SUPPORT_CASE_OPENED` timeline event is written on
the linked entity immediately.

### Assigning to an operator

```bash
curl -s -X POST "$BASE/api/v2/admin/support/cases/$CASE_ID/assign" \
  -H "Authorization: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ownerUserId": 7}'
```

An OPEN case is automatically transitioned to `IN_PROGRESS` on first assignment.
A `SUPPORT_CASE_ASSIGNED` event appears on the entity timeline.

### Adding investigation notes

```bash
curl -s -X POST "$BASE/api/v2/admin/support/cases/$CASE_ID/notes" \
  -H "Authorization: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "noteText":     "Confirmed: outbox event published but projection listener threw; root cause deadlock on revision column.",
    "authorUserId": 7,
    "visibility":   "INTERNAL_ONLY"
  }'
```

Notes are immutable once written.  `visibility` controls whether the note
surfaces to the merchant portal (`MERCHANT_VISIBLE`) or remains internal
(`INTERNAL_ONLY`, default).

### Closing a case

```bash
curl -s -X POST "$BASE/api/v2/admin/support/cases/$CASE_ID/close" \
  -H "Authorization: $TOKEN"
```

Closed cases reject further notes and reassignment (HTTP 409).  The
`SUPPORT_CASE_CLOSED` event is written to the timeline.

### Viewing cases for an entity

```bash
# All open cases linked to invoice 10042 for merchant 1
curl -s "$BASE/api/v2/admin/support/cases?merchantId=1&linkedEntityType=INVOICE&linkedEntityId=10042&status=OPEN" \
  -H "Authorization: $TOKEN"
```

### Typical P1 workflow using support cases

1. Engineer A opens a CRITICAL support case linked to the affected invoice.
2. Engineer A assigns it to themselves → auto-transitions to `IN_PROGRESS`.
3. Both engineers add investigation notes (immutable audit trail).
4. Root cause found → run relevant repair action (see §05 Manual Repair Actions).
5. Add a closing note referencing the repair action ID.
6. Close the case.
7. Timeline on the invoice now shows full investigation history end-to-end.
