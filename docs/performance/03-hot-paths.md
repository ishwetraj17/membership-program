# Hot Paths

A hot path is a code path where latency and throughput directly affect user experience or financial accuracy. This document describes the most critical execution paths in the system, ordered by financial consequence.

---

## The Critical Hot Path: Payment Confirmation

This is the system's most important and most complex synchronous transaction.

**Trigger:** `POST /api/v2/payments/{intentId}/confirm` — called by the gateway callback on payment success.

**Full execution flow:**

```
Request arrives with gateway payload
      ↓
1. Idempotency check (Layer 1: DB; Layer 2: Redis cache — planned)
      ↓ (idempotency miss — first time)
2. SELECT payment_intents_v2 WHERE id = ? FOR UPDATE
   [holds exclusive lock on this payment row]
      ↓
3. Validate status == 'INITIATED'
4. Validate gateway transaction ID not already used (business_fingerprint check)
      ↓
5. Write payment_transactions_v2 (gateway callback record)
      ↓
6. Update payment_intents_v2: status = 'COMPLETED', captured_amount, captured_at
      ↓
7. SELECT invoices_v2 WHERE id = pi.invoice_id FOR UPDATE
   [holds exclusive lock on the invoice row]
      ↓
8. Update invoices_v2: status = 'PAID', paid_at
      ↓
9. Write ledger_entry (type=PAYMENT_CAPTURED) + 2 ledger_lines:
      DR ACCOUNTS_RECEIVABLE
      CR CASH
      ↓
10. Write ledger_entry (type=INVOICE_SETTLED) + 2 ledger_lines:
      DR CASH — conceptual settlement
      CR ACCOUNTS_RECEIVABLE
      ↓
11. Write outbox_events (PAYMENT_CAPTURED event, at-least-once)
      ↓
12. Write idempotency_key response
      ↓
COMMIT — all 10+ writes in one PostgreSQL transaction
```

**Database writes in this one transaction:** 1 payment_transaction + 1 payment_intent update + 1 invoice update + 2 ledger_entries + 4 ledger_lines + 1 outbox_event + 1 idempotency_key = **≥ 10 writes**

**Locks held during this transaction:**
- `FOR UPDATE` on `payment_intents_v2` row
- `FOR UPDATE` on `invoices_v2` row
- PostgreSQL row locks on all inserted rows

**Invariants enforced here:**
- Payment cannot be captured twice (status check + business_fingerprint UNIQUE)
- Ledger is always in balance (double-entry: DEBIT == CREDIT per entry)
- Invoice is always in a valid paid state after a successful payment

**P99 target:** < 300ms (dominated by DB write latency)

---

## Hot Path 2: Subscription Creation

**Trigger:** `POST /api/v2/subscriptions`

**Flow:**
```
1. Idempotency check
2. Validate merchant + plan exists and is active
3. Validate no active subscription for this customer on this plan
4. Write subscriptions_v2: status = 'PENDING_ACTIVATION'
5. Write invoices_v2: first billing invoice (status = 'DRAFT')
6. Write subscription_plan_history: initial plan assignment
7. Write customer_subscription_mapping
8. Write outbox_event: SUBSCRIPTION_CREATED
9. Write idempotency_key response
COMMIT
```

**Writes:** ~6 table inserts in one transaction.

---

## Hot Path 3: Refund Creation

**Trigger:** `POST /api/v2/refunds`

**Flow:**
```
1. Idempotency check
2. Redis lock on paymentIntentId (planned; today: DB-level SELECT FOR UPDATE)
3. SELECT payment_intents_v2 FOR UPDATE
4. Sum existing refunds: SELECT SUM(amount) FROM refund_requests WHERE payment_id = ?
5. Validate: sum + new amount <= captured_amount  (ceiling check)
6. Write refund_requests: status = 'PENDING'
7. Write outbox_event: REFUND_REQUESTED
COMMIT
   ↓
(Async) Refund approval flow:
8. SELECT refund_requests FOR UPDATE
9. Update status = 'APPROVED'
10. Call gateway refund API
11. Update status = 'COMPLETED', gateway_refund_id
12. Write ledger_entry (type=REFUND_ISSUED):
    DR REFUND_EXPENSE
    CR CASH
13. Update payment_intents_v2.refunded_amount
14. Write outbox_event: REFUND_COMPLETED
COMMIT
```

**Note:** Steps 1–7 are synchronous (respond to API caller). Steps 8–14 are async (triggered by outbox processor). The sync path intentionally does not block on gateway.

---

## Hot Path 4: Dunning Retry (Async but Volume-Sensitive)

**Trigger:** `@Scheduled(cron = "0 0 9 * * *")` daily at 09:00

**Flow per attempt:**
```
1. SELECT subscriptions_v2 WHERE status = 'PAST_DUE' FOR UPDATE SKIP LOCKED
   (batch of N subscriptions at a time)
2. Per subscription:
   a. Check dunning policy (max attempts, inter-attempt delay)
   b. Select existing attempts count
   c. If within policy: initiate new payment intent
   d. Write dunning_attempts record
   e. Write outbox_event
3. COMMIT per subscription (not one giant TX)
```

**Why NOT one giant TX:** If one subscription fails, others should not be rolled back. Each subscription is processed in its own transaction.

---

## Hot Path 5: Nightly Revenue Recognition

**Trigger:** `@Scheduled(cron = "0 30 1 * * *")` at 01:30

**Flow:**
```
1. Acquire JobLock 'revenue_recognition_daily'
2. SELECT revenue_recognition_schedules
   WHERE status = 'PENDING' AND recognition_date <= CURRENT_DATE
   (up to MaxBatchSize rows, ordered by recognition_date)
3. Per row (REQUIRES_NEW transaction):
   a. Recheck status == 'PENDING' (race condition guard)
   b. Post ledger entry:
      DR DEFERRED_REVENUE
      CR REVENUE_RECOGNIZED
   c. Update status = 'RECOGNIZED', recognized_at = NOW()
4. Release JobLock
```

**Why REQUIRES_NEW per row:** If the nightly job crashes at row 5000/10000, rows 1–4999 are committed. On restart, the job finds `PENDING` rows from 5000 onward and continues. Without REQUIRES_NEW, a crash would roll back all 4999 already-posted entries.

---

## Hot Path 6: Gateway Routing Decision

**Trigger:** Every `POST /api/v2/payments/{intentId}/confirm` and payment intent creation — `PaymentRoutingServiceImpl.selectGatewayForAttempt()`.

**Why it matters:** This path runs on every payment. Without caching, each routing decision requires 2–3 DB lookups (merchant rules, platform defaults, gateway health) per attempt, creating significant read pressure under load.

**Flow (with Redis cache warm):**

```
1. routingRuleCache.get("42", "CARD", "INR", 1)   → HIT  ──► returns List<Rule>
   [fallback: SELECT * FROM gateway_route_rules …   → populates cache]
      ↓
2. gatewayHealthCache.get("razorpay")              → HIT  ──► returns GatewayHealthStatus
   [fallback: SELECT * FROM gateway_health WHERE gateway_name=?]
      ↓
3. Walk rules in priority order — select first non-DOWN gateway
      ↓
4. Build RoutingDecisionSnapshot (serialised to JSON via codec)
      ↓
5. Return RoutingDecisionDTO (selectedGateway, ruleId, isFallback, snapshotJson)
```

**Cache warm (HIT) DB hit count:** 0 (zero DB queries)
**Cache cold (MISS) DB hit count:** 1–2 queries (rules + health per gateway)

**Snapshot persistence:** After attempt creation, `PaymentIntentV2ServiceImpl` writes the serialised snapshot to `payment_attempts.routing_snapshot_json`. This allows post-hoc audit of exactly which rules and health statuses drove a given routing outcome.

**Cache TTLs:**
- Routing rules: 300 s (5 min) — invalidated immediately on any rule mutation
- Gateway health: 60 s — refreshed via `PUT /api/v2/admin/gateway-health/{name}`

**Admin observability:** `GET /api/v2/admin/gateway-routes/routing-cache` returns current TTL configuration and key pattern. `GET /api/v2/admin/gateway-health/routing-decisions/{attemptId}` returns the snapshot for a specific attempt.

---

## Write Budget Per Hot Path

| Path | DB Writes per Request | Locks Held | TX Duration |
|---|---|---|---|
| Payment confirmation | 10+ | 2 FOR UPDATE | ~50-150ms |
| Subscription creation | 6 | 0 FOR UPDATE | ~20-50ms |
| Refund creation (sync) | 3 | 1 FOR UPDATE | ~15-30ms |
| Dunning retry (per sub) | 3-4 | 1 SKIP LOCKED | ~20-40ms |
| Revenue recognition (per row) | 4 | 0 FOR UPDATE | ~10-20ms |

---

## Contention Hot Spots

These are the specific DB operations where lock contention is highest:

1. **`payment_intents_v2` FOR UPDATE** — gateway and API may both try to update the same intent
2. **`subscriptions_v2` @Version** — dunning + cancel + webhook update race
3. **`ledger_lines` bulk insert** — during nightly revenue recognition on large datasets
4. **`outbox_events` SKIP LOCKED polling** — high-throughput outbox with many pollers

For each, see [06-concurrency-model.md](../architecture/06-concurrency-model.md) for the guard mechanism.

---

## Measuring Hot Path Health

Key metrics to monitor:

```sql
-- Average and P99 payment confirmation time
SELECT
  DATE_TRUNC('hour', captured_at) AS hour,
  COUNT(*) AS captures,
  PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY latency_ms) AS p99_ms
FROM payment_capture_metrics
WHERE captured_at > NOW() - INTERVAL '24 hours'
GROUP BY 1;

-- Subscription creation rate
SELECT DATE_TRUNC('minute', created_at) AS minute, COUNT(*)
FROM subscriptions_v2
WHERE created_at > NOW() - INTERVAL '1 hour'
GROUP BY 1
ORDER BY 1;

-- Outbox processing lag
SELECT
  AVG(EXTRACT(EPOCH FROM (processed_at - created_at))) AS avg_lag_seconds,
  MAX(EXTRACT(EPOCH FROM (processed_at - created_at))) AS max_lag_seconds
FROM outbox_events
WHERE processed_at > NOW() - INTERVAL '1 hour';
```

---

## Hot Path 6: Rate Limiting (Redis Sliding Window)

Rate limiting runs **before** every annotated endpoint handler via `RateLimitInterceptor`.
It must be sub-millisecond to avoid adding meaningful latency to the hot paths above.

### How it works

1. `RateLimitInterceptor.preHandle` reads the `@RateLimit` annotation on the handler method.
2. For each policy, it calls `RedisSlidingWindowRateLimiter.checkLimit(policy, subjects...)`.
3. The limiter executes an atomic Lua script on a Redis sorted set:
   - `ZREMRANGEBYSCORE` — remove entries outside the current window.
   - `ZCARD` — count current entries.
   - If count < limit: `ZADD` + `PEXPIRE` and return `[1, remaining, resetMs]`.
   - Else: return `[0, 0, resetMs]`.
4. On allow: response headers `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` are set.
5. On deny: `RateLimitExceededException` is thrown → `GlobalExceptionHandler` returns HTTP 429 + `Retry-After`.
6. If Redis is unavailable: **always-permit** fallback (no latency added, no false-positive blocks).

### Policies

| Policy | Key pattern | Default limit | Window |
|---|---|---|---|
| `AUTH_BY_IP` | `{env}:firstclub:rl:auth:ip:{ip}` | 20 req | 5 min |
| `AUTH_BY_EMAIL` | `{env}:firstclub:rl:auth:user:{email}` | 10 req | 15 min |
| `PAYMENT_CONFIRM` | `{env}:firstclub:rl:payconfirm:{merchantId}:{customerId}` | 10 req | 10 min |
| `WEBHOOK_INGEST` | `{env}:firstclub:rl:webhook:{provider}:{ip}` | 200 req | 1 min |
| `APIKEY_GENERAL` | `{env}:firstclub:rl:apikey:{merchantId}:{keyPrefix}` | 1 000 req | 1 min |

All limits are overridable via `app.rate-limit.policies.<POLICY_NAME>.limit` and `.window-seconds`.

### Audit table

Blocked requests are recorded in `rate_limit_events` (best-effort, non-blocking write).
The deep-health endpoint exposes `rateLimitBlocksLastHour` from this table.

```sql
-- Recent rate limit blocks by policy
SELECT category, COUNT(*) AS blocks
FROM rate_limit_events
WHERE blocked = true AND created_at > NOW() - INTERVAL '1 hour'
GROUP BY category
ORDER BY blocks DESC;
```

---

## Phase 19: Instrumentation Points and Lag Budgets

### Where to Add Micrometer Metrics

These points are not yet instrumented and represent the highest-value observability gaps:

| Location | Metric name | Type | Why |
|---|---|---|---|
| `OutboxService.lockDueEvents()` return | `outbox.pending` | Gauge | Queue depth at each poll cycle |
| `OutboxService.processSingleEvent()` success | `outbox.processed.total` | Counter | Throughput measurement |
| `OutboxService.processSingleEvent()` failure | `outbox.failed.total` | Counter (exists) | Already wired; confirm labels |
| Idempotency cache hit in `RedisIdempotencyStore` | `idempotency.cache.hit` | Counter | Proves Redis is saving DB reads |
| Idempotency cache miss | `idempotency.cache.miss` | Counter | Informs TTL tuning |
| `JobLockService.acquireLock()` returns false | `scheduler.lock.contention` | Counter | Double-fire detection |
| `MerchantWebhookDeliveryServiceImpl` auto-disable | `webhook.endpoint.autodisabled` | Counter | Reliability signal |
| Projection update listener queue depth | `projection.listener.queue.depth` | Gauge | Projection lag signal |

### Lag Budget Definitions

| Component | Acceptable lag | Alert threshold | Measurement method |
|---|---|---|---|
| Outbox event → handler execution | < 5 s | > 30 s (P95) | `outbox.pending` gauge × poll interval |
| Projection listener → read-model update | < 10 s | > 60 s | Timestamp diff between event and projection update |
| Webhook delivery → first attempt | < 30 s | > 5 min | `merchant_webhook_deliveries.scheduled_at - created_at` |
| Renewal dunning → first charge attempt | < 2 h | > 6 h | `dunning_attempts.created_at - subscription.renews_at` |
| Reconciliation T+1 → completion | < 2 h | > 6 h | `recon_reports.completed_at - report_date` |

### Outbox Lag Query

```sql
-- Current outbox backlog depth by status
SELECT status, COUNT(*) AS count, MIN(created_at) AS oldest
FROM outbox_events
GROUP BY status;

-- Events stuck in PROCESSING (stale lease indicator)
SELECT id, event_type, created_at, attempts, lease_holder
FROM outbox_events
WHERE status = 'PROCESSING'
  AND updated_at < NOW() - INTERVAL '10 minutes';
```

### Projection Rebuild Time Baseline

Run the following after a cold start to get a baseline rebuild duration:

```sql
-- Time the last full subscription projection rebuild
SELECT
    MIN(created_at) AS rebuild_start,
    MAX(updated_at) AS rebuild_end,
    EXTRACT(EPOCH FROM (MAX(updated_at) - MIN(created_at))) AS duration_seconds
FROM subscription_projections
WHERE updated_at > NOW() - INTERVAL '1 hour';
```

Expected: < 30 s for 10K active subscriptions on a single Postgres node.

### Retry Count Distribution

```sql
-- Outbox retry distribution (high attempts = slow handlers or persistent errors)
SELECT attempts, COUNT(*) AS events
FROM outbox_events
WHERE created_at > NOW() - INTERVAL '24 hours'
GROUP BY attempts
ORDER BY attempts;

-- Webhook delivery retry distribution
SELECT attempts, COUNT(*) AS deliveries
FROM merchant_webhook_deliveries
WHERE created_at > NOW() - INTERVAL '24 hours'
GROUP BY attempts
ORDER BY attempts;
```

Expected: > 95% of events succeed at `attempts = 1`. More than 5% at `attempts >= 3`
indicates a systematic handler or connectivity issue.


---

## Phase 20 — Hot Path: Deep Health and System Summary Endpoints

### GET /api/v2/admin/system/health/deep — Query Budget

This endpoint now executes the following DB queries per call (all read-only):

| Query | Typical Cost |
|-------|-------------|
| outboxEventRepository.countByStatus x3 | < 5 ms with index on status |
| deadLetterMessageRepository.count | < 2 ms |
| webhookDeliveryRepository.countByStatus x2 | < 5 ms |
| revRecogRepository.countByStatus | < 5 ms |
| reconMismatchRepository.countByStatus | < 5 ms |
| featureFlagRepository.count | < 2 ms |
| dunningAttemptRepository.countByStatus | < 5 ms |
| integrityCheckFindingRepository.countByStatus | < 5 ms |
| integrityCheckRunRepository.findFirstByOrderByStartedAtDesc | < 3 ms |
| redisAvailabilityService.getStatus | 0 ms (cached) |

Total budget: < 50 ms P99 under normal load. Do not call more than once per minute from automated monitors.

### GET /api/v2/admin/system/summary — Query Budget

Runs the same queries as deep health plus:
- `jobLockRepository.findAllByOrderByJobNameAsc` — O(N) on job count, typically < 50 rows, < 3 ms

### GET /api/v2/admin/system/scaling-readiness — No DB Cost

Returns a fully static in-memory response. Zero database queries. Safe to call at any frequency.
