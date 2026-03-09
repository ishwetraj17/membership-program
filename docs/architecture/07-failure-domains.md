# Failure Domains

This document maps what fails under specific infrastructure or application-level problems, what degrades gracefully, and what the repair path is.

---

## 1. PostgreSQL Down

**What breaks immediately:**
- All write API endpoints (subscription create, payment, refund, dispute, invoice)
- All read endpoints that query source tables (no read cache deployed yet)
- Scheduled jobs (revenue recognition, reconciliation, dunning, snapshots)
- Outbox polling (cannot read `domain_events_outbox`)
- Idempotency enforcement (cannot read `idempotency_keys`)

**What remains safe:**
- No partial financial state — all writes are ACID; nothing is committed if DB is down
- Health check (`GET /ops/health/deep`) will return `DOWN` immediately

**Repair path:**
1. Restore PostgreSQL connectivity
2. Verify Flyway schema is intact (`SELECT count(*) FROM flyway_schema_history`)
3. Check for in-flight transactions at failure time (PostgreSQL crash-safe; WAL-based recovery handles this)
4. Restart application if connection pool is fully poisoned
5. Manually trigger any missed scheduled jobs via `POST /ops/jobs/trigger?name={jobName}` (if this endpoint is implemented) or by direct service call in a maintenance window

---

## 2. Redis Down

**What breaks:**
- Idempotency fast-path cache (Redis read)
- Rate limiting enforcement (Redis counters)
- Dedup short-circuit (Redis SET NX)
- Routing cache lookups
- Projection caches (returns cache miss)
- Feature flag cache (returns cache miss)

**What degrades gracefully:**
- Idempotency: falls back to DB check (slower, but correct)
- Feature flags: falls back to DB query on every request
- Projection reads: falls back to source-table query
- Routing: falls back to DB-based routing rule evaluation
- **Rate limiting: always-permit fallback — no false-positive blocks.** All requests are served. This is a temporary protection gap (acceptable; not a data-correctness issue). See domain 10 below.

**What remains safe:**
- All financial writes are PostgreSQL-backed; Redis unavailability cannot cause lost money or double-charges
- `global-rules.md` rule 3: "Redis unavailable → DB fallback must preserve correctness; never fail-open on financial ops"

**Repair path:**
- Redis is stateless with respect to financial data — simply restart/restore Redis
- Warm caches will rebuild on first access after Redis recovery
- No data migration required

---

## 3. Gateway Timeout / Failure

**What breaks:**
- `PaymentAttemptV2` is created with `status=INITIATED` but never updated
- Customer-facing session hangs or times out

**What degrades:**
- Payment intent left in `CREATED` or `ATTEMPTED` state
- Invoice left in `PENDING` state

**What remains safe:**
- No ledger entries posted for failed/incomplete gateway calls
- No double-charge possible from the system's side if gateway never confirms

**Repair path:**
1. Dunning engine picks up PAST_DUE subscriptions and retries payment
2. Merchant can manually trigger retry via admin API
3. If gateway sends a late SUCCESS callback after timeout: idempotency guard prevents double-posting
4. If gateway never responds: after `max_attempts` the intent is marked `FAILED`; dunning cycle continues

---

## 4. Duplicate Webhook from Gateway

**What happens:**
- Gateway sends same event twice (common in real gateway integrations)
- First delivery: processed normally, ledger entry posted
- Second delivery: hits dedup layer

**What prevents double-money (Phase 6 — two-tier dedup):**
- **Tier 1 — Redis fast path:** `SET NX EX 3600` on `{env}:firstclub:dedup:webhook:{provider}:{eventId}`.
  Returns DUPLICATE in < 1 ms without touching the DB.
- **Tier 1 fallback — payload hash:** If `eventId` is absent, SHA-256 of raw payload is checked
  against `{env}:firstclub:dedup:webhookfp:{provider}:{hash}` (TTL 300 s).
- **Tier 2 — DB authoritative check:** `webhook_events.event_id` UNIQUE constraint and
  `processed = true` flag catch duplicates even after Redis failover.
- **Business-effect dedup:** `business_effect_fingerprints` UNIQUE(effect_type, fingerprint) ensures
  that even if a duplicate webhook bypasses the event-id check, the downstream business effect
  (e.g. payment capture ledger entry) is recorded at most once.
- **Pre-Phase-6 guards (still active):** `payment_attempts_v2.business_fingerprint` UNIQUE constraint,
  `gateway_txn_id` UNIQUE on payments, `SELECT FOR UPDATE` on intent state machine.

**When Redis is unavailable:**
- Tier 1 fast-path is skipped; platform proceeds directly to Tier 2 DB check.
- Correctness is maintained — DB guard is always active.
- Protect against this window with monitored Redis uptime.

**Diagnostic:**
- `GET /api/v2/admin/webhooks/dedup/{provider}/{eventId}` — shows Redis marker + DB processed status.

**Result:** Second and subsequent webhook deliveries return 200 (idempotent) without posting any new ledger entry.

**Repair path:** None needed — the guard works. Manual investigation: check `webhook_events` for `event_id`, check `business_effect_fingerprints` for the effect type + fingerprint, check `ledger_entries` count for the reference — should be exactly 1.

---

## 5. Outbox Handler Failure

**Scenario:** Outbox poller encounters an error (DB connectivity blip, deserialization error, event listener bug) while processing a batch of outbox events.

**What happens:**
- Outbox events remain in `PENDING` or are moved to `DLQ` after threshold failures
- Business state is already committed (outbox was written in same TX as the business write)
- Downstream projections and webhooks do not receive the event until the DLQ is drained

**What remains safe:**
- Financial state (subscriptions, invoices, payments) is correct — it was committed before the outbox poller ran
- No data loss — events sit in the outbox table indefinitely until processed

**What degrades:**
- Projection lag increases
- Webhook delivery delayed
- Timeline views stale

**Repair path:**
1. `GET /ops/outbox/dlq` — inspect failed events
2. `GET /ops/health/deep` — shows outbox lag count
3. Fix the root cause (deserialization issue, downstream error)
4. `POST /ops/outbox/replay/{id}` — replay specific DLQ event
5. `POST /ops/outbox/replay/all` — bulk replay if safe

---

## 6. Projection Update Failure

**Scenario:** `ProjectionEventListener` fails to update `subscription_projections` after a subscription state change.

**What happens:**
- Source table (`subscriptions_v2`) has correct state
- Projection table lags behind by one event
- Dashboard may show stale subscription status

**What remains safe:**
- All financial operations read from source tables, not projections
- Support reads that require exact state go to source tables

**What degrades:**
- Merchant dashboard reads stale data until projection catches up

**Repair path:**
1. `POST /ops/projections/rebuild?name=subscription_status` — triggers full rebuild
2. Rebuild reads all `domain_events` for the projection's aggregate and replays them
3. During rebuild: serve reads from source table (projection reads should fall back to source on miss)

---

## 7. Scheduler Duplicate Fire

**Scenario:** Application restarts during a scheduled job, causing the same cron expression to fire twice when the new instance starts.

**What happens:**
- First fire: acquires `JobLock`, runs job
- Second fire (concurrent or rapid re-fire): `acquireLock()` returns false → job exits immediately

**What remains safe:**
- Job-lock INSERT ON CONFLICT pattern ensures mutual exclusion
- Revenue recognition: idempotency guard (`REQUIRES_NEW` + status check) on each schedule row

**Result:** Zero duplicate side effects from duplicate scheduler fires.

---

## 8. Stale Optimistic Lock Update

**Scenario:** Two concurrent requests both load a `SubscriptionV2` at `version=3`. One commits first (version becomes 4). The second tries to commit and gets `ObjectOptimisticLockingFailureException`.

**What happens:**
- Second request transaction is rolled back
- Second request receives `409 Conflict` or `422 Unprocessable Entity`
- No partial state is written

**What remains safe:**
- Subscription state is always consistent — no partial update ever persists
- The losing request saw a stale view, but nothing was overwritten silently

**Repair path:**
- Client retries the operation after reading latest state
- Application logs include `subscriptionId`, `expectedVersion`, `actualVersion`

---

## 9. Partial Repair Action Failure

**Scenario:** An operator triggers a manual repair action (e.g., mark a mismatch as RESOLVED, retry a failed outbox event) and the repair action fails midway.

**What happens:**
- Repair actions are designed to be idempotent
- Partial failure means the repair is not yet applied — the entity remains in its pre-repair state
- Retry of the repair action should be safe

**What remains safe:**
- No repair action mutates financial ledger entries directly
- Repair actions that do post new entries (e.g., manual reversal) go through the same `LedgerServiceImpl.postEntry()` with balance validation

**Repair path:**
- Check current entity state
- Re-trigger the repair action (idempotent)
- If truly stuck: escalate to manual SQL in a maintenance transaction with review

---

## Failure Domain Matrix

| Failure | Breaks Writes | Breaks Reads | Breaks Async | Financial Safety | Recovery |
|---|---|---|---|---|---|
| PostgreSQL down | ✓ | ✓ | ✓ | ✓ (no partial state) | Restore DB; restart app |
| Redis down | Partial (rate limiting gap) | Degrades to DB | No | ✓ | Restart Redis; caches self-heal |
| Gateway timeout | Payment path only | No | No | ✓ | Dunning retries |
| Duplicate webhook | No | No | No | ✓ (idempotency guards) | None needed |
| Outbox failure | No | No | ✓ | ✓ | Drain DLQ; replay events |
| Projection failure | No | Stale dashboard | No | ✓ | Rebuild projection |
| Scheduler duplicate | No | No | Noop (job lock) | ✓ | None needed |
| Stale optimistic lock | Request fails (correct) | No | No | ✓ | Client retries |
| Partial repair | Repair only | No | No | ✓ | Retry repair action |
| Rate limit burst | No (requests blocked) | No | No | ✓ | Auto-reset at window end |

---

## 10. Rate Limit Failure Modes

### Rate limiter allows too much (Redis down)

When Redis is unavailable the limiter enters **always-permit** mode — all requests pass through
regardless of volume. This is intentional: a false-positive block would be worse than a
temporary protection gap for legitimate users.

**Mitigation:** Cloud-managed Redis (e.g. ElastiCache) with Multi-AZ. Monitor
`GET /api/v2/admin/system/redis/health` and `rateLimitBlocksLastHour` in the deep-health report.

### Rate limiter blocks a legitimate user

If the limits are too tight (e.g. after a mobile release drives a burst) operators can
temporarily raise limits via `application.properties` config overrides without redeployment
(when using a config server), or by bouncing the rate-limit instance with updated env vars.

**Recovery steps:**
1. Check `GET /api/v2/admin/system/rate-limits` to see current effective limits.
2. Review `SELECT * FROM rate_limit_events WHERE created_at > NOW() - INTERVAL '1 hour'` to confirm the block.
3. Raise `app.rate-limit.policies.<POLICY>.limit` or reduce `.window-seconds`.
4. Affected users are automatically unblocked when their current window expires.

---

## What Retries Are Safe vs Unsafe

### Safe to Retry

| Operation | Why |
|---|---|
| `POST /api/v2/subscriptions` with same idempotency key | Returns same response from cache |
| `POST /api/v2/payments/intents` with same idempotency key | Returns same intent |
| `POST /api/v2/payments/confirm` with same `gateway_txn_id` | Idempotent — second confirm returns 200 |
| Revenue recognition post for already-`POSTED` schedule | `REQUIRES_NEW` guard skips it |
| Reconciliation run for same date | Idempotent design |
| Outbox event replay | Dedup guards prevent re-posting money effects |
| Repair action retry | All repair actions designed as idempotent |

### Unsafe to Retry

| Operation | Why |
|---|---|
| `POST /api/v2/payments/intents` **without** idempotency key | Creates a new intent each time |
| `POST /api/v2/refunds` **without** idempotency key | Creates a separate refund each time |
| Manual ledger entry post | If not idempotent, posts duplicate journal |
| Gateway callback without idempotency check | Would post double ledger entry |
| Projection rebuild during active event stream | May miss the last few events if not locked |

---

## 11. Data Integrity Violations

Data integrity violations are subtle divergences between related tables that bypass application logic (e.g., via direct DB writes, migration bugs, or silent service failures).

**Examples detected by the Unified Invariant Engine:**
- Invoice `grand_total` ≠ sum of its lines (`billing.invoice_total_equals_line_sum`)
- Total completed refunds exceeded payment captured amount (`payments.refund_within_refundable_amount`)
- Ledger entry is unbalanced — DEBIT ≠ CREDIT (`ledger.entry_balanced`)
- POSTED revenue recognition schedule has no ledger link (`revenue.posted_schedule_has_ledger_link`)
- Payment appears in multiple POSTED settlement batches (`recon.payment_in_at_most_one_batch`)

**What stays safe:**
- The violation is detected, not caused, by the engine — the engine is read-only
- No in-flight transactions are affected

**Detection path:**
1. Admin triggers `POST /api/v2/admin/integrity/check` (or scheduled via cron)
2. Each checker queries the DB and produces an `IntegrityCheckFinding` with violations listed
3. Run result persisted to `integrity_check_runs` + `integrity_check_findings`
4. `failedChecks > 0` → alert ops team

**Repair path:**
1. Identify the failing invariant from `GET /api/v2/admin/integrity/runs/{runId}`
2. Reference the `suggestedRepairKey` in each finding
3. Execute the corresponding repair action in `05-manual-repair-actions.md`
4. Re-run the specific checker: `POST /api/v2/admin/integrity/check/{invariantKey}`
5. Confirm the finding is now `PASS`

**Severity guide:**

| Severity | Response |
|---|---|
| CRITICAL | Immediate incident — financial data is inconsistent |
| HIGH | Same-day investigation — potential data quality issue |
| MEDIUM | Next business day — review and monitor |
| LOW | Track in backlog — cosmetic or tracing metadata issue |

---

## Phase 9 — Concurrency Failure Domain Hardening

Phase 9 closed a set of residual failure domains that were previously classified as "accepted races" or had no explicit guard. The following domains now have hardened isolation controls.

### Newly Hardened Failure Domains

| Domain | Failure Mode Before Phase 9 | Failure Mode After Phase 9 |
|---|---|---|
| Revenue recognition | Two concurrent `postSingleRecognition` calls could both see `PENDING`, both post ledger entries → duplicate revenue (`CRITICAL`) | `SELECT FOR UPDATE` serialises all callers; second caller sees `POSTED` and exits without posting |
| Dunning processing | Two scheduler pods both pick same due attempt → double-charge (`CRITICAL`) | `FOR UPDATE SKIP LOCKED` gives each pod a disjoint batch; no shared attempts |
| Webhook delivery | Two pods both dispatch same webhook → double event delivery (`HIGH`) | `FOR UPDATE SKIP LOCKED` gives each pod disjoint deliveries |
| Recon report upsert | Concurrent runs for same date perform interleaved delete+insert → corrupted report row (`HIGH`) | `SELECT FOR UPDATE` on the report row serialises all upsert operations for a given date |
| Payment attempt numbering | `COUNT(*)` with no serialisation → two concurrent confirms could assign the same attempt number (DB constraint violation) (`MEDIUM`) | `MAX(attempt_number)` query; primary guard remains `@Version` on `PaymentIntentV2` |
| OCC exceptions (all domains) | `ObjectOptimisticLockingFailureException` surfaced as HTTP 500 (`HIGH`) | Translated to HTTP 409 with entity context and MDC in `GlobalExceptionHandler` |

### Remaining Residual Races (Accepted)

The following races are explicitly accepted with rationale:

| Domain | Residual Race | Why Accepted |
|---|---|---|
| Payment attempt numbering | `MAX(attempt_number)` is a best-effort read; two concurrent callers could still race to the same max before saving. DB unique constraint on `(payment_intent_id, attempt_number)` is the last backstop. | `@Version` on `PaymentIntentV2` ensures only one confirm succeeds; numbered attempts are a sub-concern inside a serialized confirm |
| Revenue recognition | Multiple PENDING schedules in one batch; if postSingleRecognition is called concurrently for different schedule IDs, each gets its own lock and they can proceed in parallel | This is the intended concurrent execution model — only same-ID conflicts need serialisation |
| Projection / timeline cache | Eventual consistency lag | Read concern; does not affect write integrity |
