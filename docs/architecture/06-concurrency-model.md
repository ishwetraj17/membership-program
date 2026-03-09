# Concurrency Model

This document describes every domain where concurrent writes can occur and exactly what guards are applied. Financial correctness requires DB-level guarantees everywhere money moves. Redis markers are supplemental only.

---

## General Principles

1. **PostgreSQL is the final arbiter.** For every financial write, the correctness guarantee must be achievable with Redis completely unavailable.
2. **Optimistic locking** is preferred for entity state machines (subscriptions). It fails fast and the client can retry.
3. **Unique constraints** are the cheapest and most bulletproof dedup guard.
4. **SELECT FOR UPDATE** is used when a read-then-write must be atomic and the window is known to be small.
5. **FOR UPDATE SKIP LOCKED** is used for fair batch job distribution across concurrent workers.
6. **Redis short-lived markers** are supplemental to reduce DB contention at high volume — they never replace DB constraints.

---

## Domain-by-Domain Concurrency Model

---

### Subscription

**Risk:** Two concurrent requests could both succeed in creating a subscription for the same customer + plan, resulting in duplicate active subscriptions.

| Guard | Mechanism |
|---|---|
| Redis NX lock (Layer 3) | `SET NX EX 30` on `{env}:firstclub:idem:lock:{merchantId}:{rawKey}` — first concurrent request wins; others get `409 IDEMPOTENCY_IN_PROGRESS` immediately |
| Redis response cache (Layer 2) | Cached `IdempotencyResponseEnvelope` prevents any DB query for repeat requests within TTL |
| DB primary key | Composite PK `"{merchantId}:{rawKey}"` on `idempotency_keys` — final guard when Redis is unavailable |
| State machine | `@Version` optimistic locking on `subscriptions_v2.version` — any concurrent state change causes `OptimisticLockException` |
| Uniqueness | Unique constraint on `(merchant_id, customer_id, plan_id, status=ACTIVE)` prevents duplicate active subscriptions per plan |

**Accepted race:** Two concurrent upgrades to different tiers. Whichever commits first wins; the second fails with `OptimisticLockException` and the client is instructed to retry after reading current state.

---

### Invoice Number Generation

**Risk:** Two concurrent invoice creations could generate the same invoice number.

| Guard | Mechanism |
|---|---|
| Atomic sequence | `SELECT ... FOR UPDATE` on `invoice_sequences WHERE merchant_id=?` inside the billing transaction — increments `current_number` atomically |

**Result:** Invoice numbers are guaranteed unique per merchant, no gaps possible.

---

### Payment Intent

**Risk:** Two concurrent payment intent creates for the same invoice could result in double-charging.

| Guard | Mechanism |
|---|---|
| Idempotency key | DB composite PK `"{merchantId}:{rawKey}"` on `idempotency_keys` (with Redis NX lock as fast-path guard) |
| Invoice status | Intent creation checks invoice `status=PENDING`; once an intent succeeds the invoice moves to `PAID` — subsequent intents are blocked |
| Unique active intent | Unique constraint or service-layer check: one active (non-terminal) intent per invoice |

---

### Payment Confirmation (Capture)

**Risk:** Gateway may send duplicate SUCCESS callbacks for the same transaction. Two concurrent confirmations could post two ledger entries.

| Guard | Mechanism |
|---|---|
| Business fingerprint | UNIQUE constraint on `payment_attempts_v2.business_fingerprint` — computed from `(intent_id, gateway_txn_id, outcome)` |
| Intent terminal state | `SELECT FOR UPDATE` on `PaymentIntentV2` — if already `SUCCEEDED`, reject the second confirmation |
| Idempotency fast-path | Redis response cache (`{env}:firstclub:idem:resp:…`) checked before DB; on HIT returns cached response with zero DB I/O |
| **Phase 6 — webhook event-id dedup** | `{env}:firstclub:dedup:webhook:{provider}:{eventId}` SET NX TTL 3600 s — pre-DB fast-path in `WebhookDedupService.checkByEventId()` |
| **Phase 6 — payload-hash dedup** | `{env}:firstclub:dedup:webhookfp:{provider}:{hash}` SET NX TTL 300 s — fallback when `eventId` is absent |
| **Phase 6 — business-effect dedup** | `{env}:firstclub:dedup:biz:payment_capture_success:{fingerprint}` SET NX TTL 86400 s, backed by UNIQUE in `business_effect_fingerprints` |

**Result:** Exactly one ledger entry per payment capture, regardless of duplicate gateway callbacks.

---

### Refund

**Risk:** Two concurrent refund requests for the same payment could both see `refunded_amount=0` and both approve, resulting in `refunded_amount > captured_amount`.

| Guard | Mechanism |
|---|---|
| **Phase 15 — fingerprint idempotency** | SHA-256 fingerprint stored in `refunds_v2.request_fingerprint` (UNIQUE partial index) — replay of any request with an existing fingerprint returns the cached refund without touching the DB write path |
| **Phase 15 — Redis pre-lock** | `{env}:firstclub:refund:lock:{paymentId}` SET NX TTL 10 s via `RedisKeyFactory.refundLockKey()` — reduces DB lock contention; degrades gracefully when Redis unavailable |
| SELECT FOR UPDATE | Lock the `Payment` row during refund creation |
| Application-level check | After acquiring lock: verify `refunded_amount + new_amount <= captured_amount` |

**Invariant enforced:** `refunded_amount <= captured_amount` — hard failure if violated.

---

### Dispute

**Risk:** Two concurrent dispute opens could double-reserve funds, or a resolved dispute could have its resolution accounting posted twice.

| Guard | Mechanism |
|---|---|
| Unique constraint | One OPEN dispute per payment — application-level check with SELECT FOR UPDATE on `Payment` |
| SELECT FOR UPDATE | Lock `Payment` during both `openDispute()` and `resolveDispute()` |
| **Phase 15 — Redis pre-lock** | `{env}:firstclub:dispute:lock:{paymentId}` SET NX TTL 10 s via `RedisKeyFactory.disputeLockKey()` — reduces DB contention; degrades gracefully |
| **Phase 15 — `reserve_posted` flag** | Set to `true` after `postDisputeOpen()` completes; marks accounting as done in the disputes row itself |
| **Phase 15 — `resolution_posted` flag** | Set to `true` after WON/LOST accounting completes; any second `resolveDispute()` call throws HTTP 409 `DISPUTE_RESOLUTION_ALREADY_POSTED` |

---

### Ledger Entry Posting

**Risk:** Concurrent posts could produce unbalanced entries if DB constraint is not enforced.

| Guard | Mechanism |
|---|---|
| Balance constraint | `LedgerServiceImpl.postEntry()` validates `sum(DEBIT) == sum(CREDIT)` before any persistence |
| Append-only | No UPDATE or DELETE ever runs on ledger tables — no concurrent modification risk |
| Business fingerprint | `LedgerEntry.business_fingerprint` UNIQUE constraint prevents duplicate posting |

---

### Revenue Recognition

**Risk:** Scheduler runs twice (system restart during job, or two nodes if cluster is ever introduced). Two runs could post the same schedule row twice.

| Guard | Mechanism |
|---|---|
| JobLock | `INSERT INTO job_locks (name) ... ON CONFLICT DO UPDATE SET locked_at=now() WHERE expired` — only one node/job can hold the lock |
| Status idempotency | In `REQUIRES_NEW` transaction: reload `RevenueRecognitionSchedule`; if `status=POSTED` → return without posting |
| ledger_entry_id | Not null after posting — a second attempt that tries to post will find the entry already set |

**Result:** `REQUIRES_NEW` + status check + JobLock = zero duplicate recognition entries.

---

### Reconciliation

**Risk:** Duplicate recon runs for the same date could create duplicate `ReconMismatch` rows.

| Guard | Mechanism |
|---|---|
| JobLock | Prevents simultaneous run of the reconciliation job |
| Idempotent design | Re-running for the same date does not create duplicate mismatch rows — existing OPEN mismatches are preserved, new ones are added, resolved ones are not touched |

---

### Idempotency Key Registration

**Risk:** Two concurrent requests with the same idempotency key could both pass the check and both proceed.

| Guard | Mechanism |
|---|---|
| DB UNIQUE constraint | `UNIQUE(merchant_id, idempotency_key, endpoint)` on `idempotency_keys` — the second INSERT fails |
| In-flight Redis lock (future) | `{env}:firstclub:idem:lock:{merchantId}:{idempotencyKey}` — SET NX with TTL; first request wins, second waits or gets 409 |
| Cached response | After first request completes: `{env}:firstclub:idem:resp:{merchantId}:{idempotencyKey}` holds the response for fast replay |

---

### Webhook Delivery

**Risk:** Two delivery workers could both pick up the same `PENDING` delivery and send it twice.

| Guard | Mechanism |
|---|---|
| FOR UPDATE SKIP LOCKED | Delivery workers use `SELECT ... FOR UPDATE SKIP LOCKED` to claim exclusive ownership of a delivery row |
| Redis delivery lock (future) | `{env}:firstclub:webhook:lock:delivery:{deliveryId}` — SET NX for additional protection |

---

### Outbox Processing

**Risk:** Two outbox pollers could both process the same event.

| Guard | Mechanism |
|---|---|
| FOR UPDATE SKIP LOCKED | Poller claims rows exclusively |
| Redis processing marker (future) | `{env}:firstclub:outbox:proc:{eventId}` — SET NX TTL 60s |
| Status check | Worker checks `status=PENDING` before processing; status update is atomic with processing |
| **Phase 6 — DedupAwareOutboxHandler** | Abstract base class wraps every `handle()` call: pre-checks `BusinessEffectDedupService.checkAndRecord()` (Redis + DB); if DUPLICATE → logs and returns without calling `applyEffect()`. Any exception from `applyEffect()` propagates normally for retry. |

---

### Business-Effect Deduplication (Phase 6)

**Risk:** At-least-once delivery guarantees (both inbound gateway webhooks and outbound outbox dispatch) mean business effects such as payment capture, refund completion, dispute opening, and revenue recognition can be triggered more than once.

| Guard | Tier | Mechanism |
|---|---|---|
| Redis fast path | 1 | `{env}:firstclub:dedup:biz:{effectType}:{fingerprint}` SET NX TTL 86400 s |
| DB durable path | 2 | INSERT INTO `business_effect_fingerprints` (effect_type, fingerprint) — caught by UNIQUE(effect_type, fingerprint) |
| Race safety | Both | `DataIntegrityViolationException` from concurrent INSERT is caught and mapped to DUPLICATE; no exception leaks to the caller |

**Fingerprint scheme — deterministic SHA-256 from business-unique fields:**

| Effect type | Fingerprint inputs |
|---|---|
| `PAYMENT_CAPTURE_SUCCESS` | `merchantId:paymentIntentId:gatewayTxnId` |
| `REFUND_COMPLETED` | `merchantId:refundId` |
| `DISPUTE_OPENED` | `merchantId:paymentId:disputeReference` |
| `SETTLEMENT_BATCH_CREATED` | `merchantId:settlementDate:CURRENCY` |
| `REVENUE_RECOGNITION_POSTED` | `rr:{scheduleId}` |

**Redis unavailable:** Tier 1 is skipped; Tier 2 DB check handles correctness.  The business
write never proceeds twice — the UNIQUE constraint is the last line of defence.

**Admin visibility:**
- `GET /api/v2/admin/dedup/business-effects?effectType={type}&since={iso8601}`
  — lists recently recorded fingerprints from `business_effect_fingerprints`.

---

### Gateway Routing Cache

**Risk:** An operator updates a routing rule (e.g., changes the preferred gateway from `razorpay` to `stripe`), but in-flight payment requests still read the stale cached rule for up to 5 minutes (300 s TTL).

| Guard | Mechanism |
|---|---|
| Targeted cache eviction | After every `createRouteRule`, `updateRouteRule`, or `deactivateRouteRule`, `RoutingRuleCache.evict(scope, methodType, currency, retryNumber)` deletes the exact key for the mutated rule's discriminators |
| TTL safety valve | Even without eviction, stale entries expire after 300 s |
| DB is always correct | On cache miss, `PaymentRoutingServiceImpl.loadRules()` re-queries the DB — the canonical state is never ambiguous |

**Accepted race:** Between the DB commit of a rule mutation and the cache eviction call, a very narrow window exists where a concurrent routing decision may use the old rule. This is acceptable because:
1. The eviction is called immediately after `routeRuleRepository.save()` returns.
2. Financial correctness is not affected — routing to either old or new gateway is valid; the operator intent is that **future** payments use the new rule.

**Health cache eviction:** `GatewayHealthCache` entries are refreshed on every `PUT /admin/gateway-health/{name}` call. The 60 s TTL caps stale-read exposure for health status changes not triggered via the admin API.

---

### Scheduled Jobs (All)

**Risk:** Scheduler fires twice (cron mis-fire, node restart, eventual multi-node deployment).

| Guard | Mechanism |
|---|---|
| JobLock | All scheduled jobs use `JobLockServiceImpl.acquireLock(name)` with INSERT-then-UPDATE-if-expired pattern |
| Lock TTL | Each lock has a `lock_expiry` — if the previous holder crashed, the lock expires and the next run can proceed |

---

## Optimistic vs Pessimistic Locking Decision

| Scenario | Choice | Reason |
|---|---|---|
| Subscription state change | Optimistic (`@Version`) | Low contention; fail-fast is acceptable; client can retry |
| Invoice number generation | Pessimistic (`SELECT FOR UPDATE`) | Critical to be sequential; window is tiny |
| Payment capture | Pessimistic (SELECT FOR UPDATE on intent) + unique constraint | Money path; must not allow concurrent double-capture |
| Refund ceiling check | Pessimistic (SELECT FOR UPDATE on payment) | Must read-then-write atomically |
| Batch job rows | FOR UPDATE SKIP LOCKED | Multiple workers; want fair distribution without lock starvation |
| Outbox polling | FOR UPDATE SKIP LOCKED | Same as batch jobs |

---

## Race Conditions Intentionally Accepted

| Race | Accepted? | Reason |
|---|---|---|
| Projection update lag | Yes | Projections are eventually consistent by design |
| Timeline cache staleness | Yes | Short TTL; support reads can fall through to source |
| Feature flag cache staleness | Yes | Flags toggle is not financially critical; TTL is acceptable |
| Webhook delivery ordering | Yes | Webhook delivery does not guarantee ordering between events |
| Revenue recognition delay | Yes | Nightly batch; day-level precision is sufficient |

---

## Phase 15 — Refund and Dispute Robustness Hardening

### Refund Fingerprint Idempotency

Refund creation is now idempotent by default. Every call to `RefundServiceV2Impl.createRefund()` computes (or accepts) a SHA-256 request fingerprint before entering the DB write path:

```
Fingerprint = SHA-256( merchantId + ":" + paymentId + ":" + amount + ":" + reasonCode )
```

| Layer | Key | TTL / Durability |
|---|---|---|
| DB unique index | `idx_refunds_v2_fingerprint` UNIQUE PARTIAL WHERE NOT NULL | Permanent — survives Redis restart |
| Redis pre-lock | `{env}:firstclub:refund:lock:{paymentId}` SET NX | 10 s — supplemental |

On replay (fingerprint match): the existing `RefundV2ResponseDTO` is returned immediately from `findByRequestFingerprint()`. No payment lock is acquired, no accounting is posted, no row is inserted.

### Dispute One-Time Posting Guards

Two boolean flags are written to the `disputes` row as each critical accounting step completes:

| Flag | Written after | Guard |
|---|---|---|
| `reserve_posted` | `postDisputeOpen()` in `openDispute()` | Prevents duplicate DISPUTE_RESERVE debit |
| `resolution_posted` | `postDisputeWon()` / `postDisputeLost()` in `resolveDispute()` | Prevents duplicate WON/LOST ledger entry; throws 409 if already posted |

These flags complement the Redis pre-lock — they are durable in-row markers that survive process crashes and Redis unavailability.

### Redis Key Summary (Phase 15)

| Key pattern | TTL | Scope |
|---|---|---|
| `{env}:firstclub:refund:lock:{paymentId}` | 10 s | Per-payment refund ingestion lock |
| `{env}:firstclub:dispute:lock:{paymentId}` | 10 s | Per-payment dispute open/resolve lock |

Both follow the platform's graceful-degradation pattern: `ObjectProvider<StringRedisTemplate>` — if `getIfAvailable()` returns `null`, the Redis step is skipped and correctness falls through to DB guards.

### New Integrity Checkers (Phase 15)

| Checker | Key | What it verifies |
|---|---|---|
| `RefundCumulativeIntegrityChecker` | `payments.refund_cumulative_consistency` | `payment.refunded_amount == SUM(COMPLETED refunds_v2)` for sampled payments |
| `DisputeReservePostedIntegrityChecker` | `payments.dispute_reserve_posted` | Every OPEN/UNDER_REVIEW dispute has `reserve_posted = true` |
| `DisputeResolutionPostedIntegrityChecker` | `payments.dispute_resolution_posted` | Every WON/LOST dispute has `resolution_posted = true` |

All three run under `runnable(merchantId)` — scoped per-merchant for on-demand admin use, or across all merchants for the nightly integrity sweep.

---

## What Breaks Under Concurrent Pressure

| Scenario | What Happens |
|---|---|
| 1000 concurrent subscription creates for same customer+plan | All but one fail with unique constraint violation (correct behavior) |
| 1000 concurrent payment confirmations for same intent | All but one fail with business_fingerprint unique violation (correct) |
| 1000 concurrent refunds for same payment | All but one fail with ceiling check after SELECT FOR UPDATE (correct) |
| Subscription state change + concurrent dunning retry | OptimisticLockException on the loser; dunning retries with fresh state (correct) |
| Two scheduler runs simultaneously | JobLock prevents both from entering critical section (correct) |

---

## Phase 9 — Concurrency Model Hardening (Formal Inventory)

Phase 9 audited every hot write domain and formalised the concurrency strategy for each.

### New Platform Classes

| Class | Purpose |
|---|---|
| `com.firstclub.platform.concurrency.ConcurrencyConflictException` | Typed 409 exception with entity type, entity ID, and `ConflictReason` — replaces raw Hibernate OCC bubbling as 500 |
| `com.firstclub.platform.concurrency.ConcurrencyGuard` | `withOptimisticLock` / `withUniqueConstraintGuard` utility; translates exceptions and logs with full MDC context (requestId, correlationId, merchantId) |
| `com.firstclub.platform.concurrency.BusinessLockScope` | Registry enum documenting each lock scope, its strategy, invariant, and failure mode |

### Per-Domain Guard Summary

| Domain | Race Closed | Guard Type | Code Location |
|---|---|---|---|
| Revenue recognition | Duplicate ledger posting (TOCTOU: both callers see PENDING, both post) | `SELECT FOR UPDATE` (pessimistic write) on schedule row | `RevenueRecognitionPostingServiceImpl.postSingleRecognition` |
| Revenue recognition | Non-pessimistic paths (e.g. test mocks) | `@Version` on `RevenueRecognitionSchedule` | `RevenueRecognitionSchedule.version` |
| Dunning attempt processing | Double-charge under multi-pod deployment (both pods pick same due attempt) | `FOR UPDATE SKIP LOCKED`, batch 50 | `DunningAttemptRepository.findDueForProcessingWithSkipLocked` |
| Webhook delivery | Double-dispatch under multi-pod deployment | `FOR UPDATE SKIP LOCKED`, batch 100 | `MerchantWebhookDeliveryRepository.findDueForProcessingWithSkipLocked` |
| Recon report upsert | Concurrent delete+insert interleave for same report date | `SELECT FOR UPDATE` (pessimistic write) on report row | `ReconciliationService` + `ReconReportRepository.findByReportDateForUpdate` |
| Payment attempt numbering | `COUNT(*)` replaced with `MAX(attempt_number)` (semantically correct; primary guard remains `@Version` on `PaymentIntentV2`) | `MAX` JPQL query + DB unique constraint on attempt sequence | `PaymentAttemptRepository.findMaxAttemptNumberByPaymentIntentId` |
| Subscription state transition | Always had `@Version`; confirmed sufficient | OCC (`@Version` on `SubscriptionV2`) | Pre-existing |
| Payment intent confirm | Always had `@Version` + `business_fingerprint` unique constraint | OCC + DB unique constraint | Pre-existing |
| Refund ceiling check | Always had `SELECT FOR UPDATE` on payment row | Pessimistic write | Pre-existing |
| Invoice sequence | Always had `SELECT FOR UPDATE` on sequence row | Pessimistic write | Pre-existing |
| Outbox event processing | Always had `FOR UPDATE SKIP LOCKED` | SKIP LOCKED | Pre-existing |

### V41 Migration — DB Changes

File: `V41__concurrency_support_indexes_and_constraints.sql`

| Change | Rationale |
|---|---|
| `revenue_recognition_schedules.version BIGINT NOT NULL DEFAULT 0` | Enables OCC backstop on recognition schedule rows |
| `uq_rrs_invoice_date UNIQUE (invoice_id, recognition_date)` | DB-level barrier against duplicate recognition rows |
| `idx_rrs_pending_date` partial index where `status = 'PENDING'` | Speeds up nightly PENDING date-range scans |
| `idx_dunning_v2_due` partial index on `dunning_attempts` | Supports efficient SKIP LOCKED batch pickup |
| `idx_webhook_delivery_due` partial index on `merchant_webhook_deliveries` | Supports efficient SKIP LOCKED batch pickup |
| `uq_recon_report_date UNIQUE (report_date)` on `recon_reports` | Prevents duplicate report rows surviving concurrent deletes |
| `idx_pi_v2_id_status` on `payment_intents_v2 (id, status)` | Supports status-filtered intent lookups |

### Exception Handling

`GlobalExceptionHandler` now includes:
- `ObjectOptimisticLockingFailureException` → HTTP 409 with `OPTIMISTIC_LOCK_CONFLICT` code, entity name, and full MDC context
- `ConcurrencyConflictException` → HTTP 409 with structured fields: entityType, entityId, ConflictReason, and full MDC context

Previously both would fall through to the generic `Exception` handler and return HTTP 500.

---

## Phase 10 — Concurrency Integration Test Suite

Six `@SpringBootTest` integration test classes in `src/test/java/com/firstclub/concurrency/`
prove that every concurrency guard from Phase 9 survives real thread contention
against a live PostgreSQL container (Testcontainers `postgres:16`).

### Test Matrix

| Class | Scenario | Guard Under Test | Threads |
|---|---|---|---|
| `SubscriptionConcurrencyIT` | 10 threads cancel the same subscription simultaneously | `@Version` OCC on `SubscriptionV2` | 10 |
| `PaymentConcurrencyIT` | 10 threads cancel the same payment intent simultaneously | `@Version` OCC on `PaymentIntentV2` | 10 |
| `RefundConcurrencyIT` | 10 × £200 refund storm on a £1,000-captured payment | Pessimistic write (`SELECT ... FOR UPDATE`) on `Payment` | 10 |
| `WebhookConcurrencyIT` | 5 threads retry the same PENDING delivery simultaneously | SKIP LOCKED on `MerchantWebhookDelivery` | 5 |
| `DunningConcurrencyIT` | 5 threads process the same SCHEDULED dunning attempt simultaneously | SKIP LOCKED on `DunningAttempt` | 5 |
| `ReconConcurrencyIT` | 10 threads run reconciliation for the same date simultaneously | Pessimistic write + `UNIQUE(report_date)` on `ReconReport` | 10 |

### Failure Modes Defended Against

| Failure Mode | Prevented By |
|---|---|
| Lost update on subscription state (two simultaneous cancels both succeed) | `@Version` OCC — second writer sees stale version and receives HTTP 409 |
| Duplicate payment intent cancel (two threads both transition ACTIVE → CANCELLED) | `@Version` OCC — exactly one winner; rest throw `ObjectOptimisticLockingFailureException` |
| Over-refund (sum of concurrent refunds exceeds captured amount) | Pessimistic `FOR UPDATE` — each refund re-reads `refundedAmount` under lock |
| Double webhook dispatch (same delivery sent twice to merchant endpoint) | SKIP LOCKED — second thread sees no rows; only first thread dispatches |
| Double gateway charge for dunning attempt | SKIP LOCKED — second thread skips the locked attempt row; `charge()` called exactly once |
| Duplicate `ReconReport` row for same date | Pessimistic write + DB `UNIQUE` constraint — one insert wins; others throw `DataIntegrityViolationException` |

### Test Infrastructure

- Extend `PostgresIntegrationTestBase` (`@SpringBootTest(webEnvironment=RANDOM_PORT)` + shared static `PostgreSQLContainer<>("postgres:16")`)
- Use `ExecutorService` (fixed thread pool) + `CyclicBarrier` (starting gun) + `CountDownLatch` (completion gate)
- `@MockitoBean` for `WebhookDispatcher` (webhook tests) and `PaymentGatewayPort` (dunning tests)
- Schema managed by `spring.jpa.hibernate.ddl-auto=create-drop`; Redis disabled (`app.redis.enabled=false`)
- Each test seeds its own data in `@BeforeEach`; common lookup records seeded once in `@BeforeAll`

### Running Phase 10 Tests in Isolation

```bash
mvn test -Dtest="SubscriptionConcurrencyIT,PaymentConcurrencyIT,RefundConcurrencyIT,WebhookConcurrencyIT,DunningConcurrencyIT,ReconConcurrencyIT"
```

Requires Docker to be running (Testcontainers auto-provisions the container).
