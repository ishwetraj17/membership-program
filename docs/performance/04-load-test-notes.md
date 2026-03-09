# Load Test Notes

This document describes the load test scenarios for the FirstClub platform, the expected behavior under each load profile, success criteria, and known failure modes.

These tests are designed to be run against a staging or production-like environment with a Postgres instance matching prod sizing (not H2). The existing test suite uses H2 in-memory; these scenarios require realistic I/O latency.

---

## Environment Prerequisites

Before running any load test:
- PostgreSQL must be running with the full prod-style schema (all Flyway migrations applied)
- At least 10 merchant accounts, each with 100–1000 active subscriptions seeded
- Redis should be available if testing with Redis enabled
- No other load tests or batch jobs running simultaneously

---

## Scenario 1: Payment Burst

**Purpose:** Verify the payment confirmation hot path holds up under concurrent gateway callbacks.

**Profile:**
```
Ramp: 0 → 500 concurrent requests over 30 seconds
Sustain: 500 concurrent for 5 minutes
Ramp down: 500 → 0 over 10 seconds
Target endpoint: POST /api/v2/payments/{intentId}/confirm
```

**Payload:** Each request confirms a unique, pre-created payment intent with randomized gateway payload.

**Success criteria:**
- P99 latency < 500ms
- Error rate < 0.1% (excluding expected 409 on concurrent duplicate confirmation)
- Zero duplicate ledger entries in `ledger_entries` (verify via query after test)
- Zero overpaid invoices (verify via `refunded_amount > captured_amount`)

**Expected failure modes:**
- 409 `OPTIMISTIC_LOCK_CONFLICT` on very hot subscriptions: acceptable, clients must retry
- 409 `CONCURRENT_REQUEST` if two identical idempotency keys arrive in flight: acceptable
- Ledger `business_fingerprint` UNIQUE violations: these indicate a duplicate gateway callback was correctly rejected

**Verify after test:**
```sql
-- No duplicate payment captures
SELECT reference_id, COUNT(*) FROM ledger_entries
WHERE entry_type = 'PAYMENT_CAPTURED'
GROUP BY reference_id HAVING COUNT(*) > 1;

-- No over-refunded payments (should be 0 rows)
SELECT * FROM payment_intents_v2 WHERE refunded_amount > captured_amount;
```

---

## Scenario 2: Subscription Creation Burst

**Purpose:** Verify that subscription creation handles concurrent requests without duplicate active subscriptions.

**Profile:**
```
Concurrent: 200 writers, each creating 50 subscriptions in sequence
Target endpoint: POST /api/v2/subscriptions
```

**Key test case:** 10 concurrent requests for the same (merchantId, customerId, planId). Exactly 1 should succeed; 9 should receive 409 `SUBSCRIPTION_ALREADY_ACTIVE`.

**Success criteria:**
- Exactly 1 active subscription per (customerId, planId)
- No orphaned invoices (invoice without a subscription)
- P99 < 300ms

**Verify after test:**
```sql
-- No customer has two active subscriptions per plan
SELECT customer_id, plan_id, COUNT(*) FROM subscriptions_v2
WHERE status = 'ACTIVE'
GROUP BY customer_id, plan_id
HAVING COUNT(*) > 1;
```

---

## Scenario 3: Webhook Delivery Duplicate Storm

**Purpose:** Verify that duplicate gateway webhook callbacks are handled idempotently.

**Profile:**
```
For each of 1000 payment intents:
  - Send 5 identical confirmation callbacks in rapid succession
  - Concurrency: 20 in-flight simultaneously
Total requests: 5000, Target: POST /api/v2/payments/{intentId}/confirm
```

**Success criteria:**
- Exactly 1 `PAYMENT_CAPTURED` ledger entry per payment intent
- First callback returns 200; subsequent duplicates return 200 (idempotency replay)
- No 500 errors

**Anti-pattern to watch:** If `business_fingerprint` UNIQUE constraint fires, it means the idempotency layer did not short-circuit early enough. This should appear as a handled exception (409 / 200), not an unhandled 500.

---

## Scenario 4: Outbox Backlog Recovery

**Purpose:** Verify the outbox poller recovers without data loss after a processing gap.

**Setup:**
1. Disable the outbox poller (stop the app or comment out `@Scheduled`)
2. Create 10,000 payment events via API → they land in `outbox_events` with `status=PENDING`
3. Re-enable the poller

**Profile:**
- Monitor outbox drain time: time from poller start → `SELECT COUNT(*) FROM outbox_events WHERE status='PENDING' = 0`
- Monitor DLQ: should stay at 0 (no failures expected in clean scenario)

**Success criteria:**
- All 10,000 events processed within 5 minutes
- Zero events in DLQ
- All corresponding `webhook_delivery_attempts` show `SUCCESS`
- Outbox lag drops to < 30 seconds within 60 seconds of poller restart

---

## Scenario 5: Revenue Recognition at Scale

**Purpose:** Verify the nightly revenue recognition job completes within its maintenance window.

**Setup:**
- 10,000 active subscriptions with 30-day plans
- Seed 30 × 10,000 = 300,000 `revenue_recognition_schedules` rows all at `status=PENDING` with `recognition_date <= CURRENT_DATE`

**Trigger:**
```
POST /api/v1/admin/revenue-recognition/run-now
```
or wait for the nightly cron.

**Success criteria:**
- All 300,000 rows transition to `RECOGNIZED` within 15 minutes
- Zero failed rows (check for `FAILED` status after run)
- Ledger entries: 300,000 `REVENUE_RECOGNIZED` entries with correct amounts
- Balance check: `DEFERRED_REVENUE` decreases by total recognized amount; `REVENUE_RECOGNIZED` increases by same amount

**Monitoring during test:**
```sql
SELECT status, COUNT(*) FROM revenue_recognition_schedules
GROUP BY status;

-- Should converge to: RECOGNIZED = 300000, PENDING = 0
```

---

## Scenario 6: Full Reconciliation on Large Dataset

**Purpose:** Verify reconciliation completes within its window on a realistic dataset.

**Setup:**
- 50,000 paid invoices
- 50,000 captured payments
- 500 intentionally mismatched records (inject: 200 AMOUNT_MISMATCH, 200 PAYMENT_NO_INVOICE, 100 INVOICE_NO_PAYMENT)

**Trigger:**
```
POST /api/v1/admin/recon/settle?date=YYYY-MM-DD
```

**Success criteria:**
- Job completes within 10 minutes
- `recon_batches.status = 'COMPLETED'`
- Exactly 500 `recon_mismatches` rows (matches injected mismatches)
- Zero `INTERNAL_ERROR` results

---

## Known Failure Modes Under Load

| Failure | Root Cause | Expected Behavior | Unacceptable |
|---|---|---|---|
| 409 on concurrent payment confirms | `business_fingerprint` UNIQUE | Client retries; second attempt hits idempotency cache | 500 error |
| Outbox lag spike during burst | Poller batch size too small | Lag increases temporarily, then recovers | No recovery |
| Revenue recognition job timeout | > 300K rows in one run | REQUIRES_NEW ensures partial commit; restart continues | Full rollback of recognized rows |
| Subscription duplicate on burst create | UNIQUE constraint fires | System rejects with 409 | Two active subs for same customer/plan |
| Ledger balance mismatch after payment burst | Double-entry violation | Should never happen; trigger P0 alert | Any imbalance |

---

## Load Test Commands (stress_test.py)

The workspace includes `stress_test.py` and `user_stress_test.py` for running targeted load:

```bash
# Payment burst
python stress_test.py --scenario payment_burst --concurrency 500 --duration 300

# Subscription burst
python stress_test.py --scenario subscription_burst --concurrency 200 --subscriptions 50

# Webhook storm
python stress_test.py --scenario webhook_storm --intents 1000 --duplicates 5
```

See stress_test.py for full parameter reference.

---

## Post-Test Audit Query Suite

Run after every load test to verify financial integrity:

```sql
-- 1. Ledger balance (all double-entry entries must net to 0)
SELECT SUM(CASE WHEN side='DEBIT' THEN amount ELSE -amount END) FROM ledger_lines;

-- 2. No duplicate captures
SELECT reference_id, COUNT(*) FROM ledger_entries
WHERE entry_type='PAYMENT_CAPTURED'
GROUP BY reference_id HAVING COUNT(*) > 1;

-- 3. No over-refunded payments
SELECT COUNT(*) FROM payment_intents_v2
WHERE refunded_amount > captured_amount;

-- 4. No active subscription duplicates
SELECT customer_id, plan_id, COUNT(*) FROM subscriptions_v2
WHERE status='ACTIVE'
GROUP BY customer_id, plan_id
HAVING COUNT(*) > 1;

-- 5. All outbox events processed
SELECT status, COUNT(*) FROM outbox_events GROUP BY status;
```

All queries should return 0 or expected clean values. Any non-zero result in queries 1–4 is a P0 incident.

---

## Phase 10 — Concurrency Integration Tests

### Purpose

Complement the Gatling load scenarios with deterministic multi-threaded integration tests that prove each database-level concurrency guard holds under real contention. These are not throughput tests — they are correctness proofs.

### Test Classes

| Class | Package | What Is Tested |
|---|---|---|
| `SubscriptionConcurrencyIT` | `com.firstclub.concurrency` | `@Version` OCC on `SubscriptionV2` — only one of 10 concurrent cancel/pause callers succeeds |
| `PaymentConcurrencyIT` | `com.firstclub.concurrency` | `@Version` OCC on `PaymentIntentV2` — only one of 10 concurrent cancel callers succeeds |
| `RefundConcurrencyIT` | `com.firstclub.concurrency` | Pessimistic `FOR UPDATE` on `Payment` — 10 × £200 refund storm never exceeds £1,000 captured amount |
| `WebhookConcurrencyIT` | `com.firstclub.concurrency` | SKIP LOCKED on `MerchantWebhookDelivery` — `WebhookDispatcher.dispatch()` called exactly once per delivery |
| `DunningConcurrencyIT` | `com.firstclub.concurrency` | SKIP LOCKED on `DunningAttempt` — `PaymentGatewayPort.charge()` called exactly once per attempt |
| `ReconConcurrencyIT` | `com.firstclub.concurrency` | Pessimistic write + `UNIQUE(report_date)` — 10 concurrent `runForDate()` calls produce exactly one `ReconReport` |

### Running

```bash
# Run concurrency tests only (Docker required for Testcontainers)
mvn test -Dtest="SubscriptionConcurrencyIT,PaymentConcurrencyIT,RefundConcurrencyIT,WebhookConcurrencyIT,DunningConcurrencyIT,ReconConcurrencyIT"

# Run as part of full suite
mvn test
```

Each class extends `PostgresIntegrationTestBase`, which starts a shared `postgres:16` container via Testcontainers. Tests use `ExecutorService` + `CyclicBarrier` to maximise thread synchronisation at the moment of contention.

### Interpreting Failures

| Symptom | Likely Cause |
|---|---|
| `successCount > 1` in subscription/payment tests | `@Version` field missing or not persisted |
| `refundedAmount > capturedAmount` | Pessimistic lock not applied in `PaymentRepository.findByIdForUpdate` |
| `dispatch()` called multiple times for same delivery | SKIP LOCKED query missing or `nextAttemptAt` filter wrong |
| `charge()` called multiple times for same attempt | SKIP LOCKED query missing or `scheduledAt` filter wrong |
| `reportCount > 1` | `UNIQUE(report_date)` constraint absent from schema |

### Post-Test Audit Queries (Concurrency)

```sql
-- Subscriptions with unexpected status
SELECT status, COUNT(*) FROM subscriptions_v2 GROUP BY status;

-- Payments refunded beyond captured amount (should return 0 rows)
SELECT id, captured_amount, refunded_amount
FROM payments
WHERE refunded_amount > captured_amount;

-- Webhook deliveries dispatched more than once (should return 0 rows)
SELECT delivery_id, COUNT(*) AS dispatch_count
FROM merchant_webhook_deliveries
GROUP BY delivery_id
HAVING COUNT(*) > 1;

-- Duplicate recon reports (should return 0 rows)
SELECT report_date, COUNT(*) FROM recon_reports GROUP BY report_date HAVING COUNT(*) > 1;
```
