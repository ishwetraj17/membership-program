# 07 — Concurrency Strategy

This document describes how the FirstClub membership platform manages concurrent writes
to shared mutable state.  It covers every isolation level and locking strategy in use,
explains when each is appropriate, and documents the pitfalls that the platform
primitives are designed to prevent.

---

## 1. The concurrency problem space

Whenever two or more requests read the same entity and then write back a modified
version, the second write can silently overwrite the first — the **lost update** anomaly:

```
T1: read(balance=100) → compute(100+10=110) → write(110)
T2: read(balance=100) → compute(100-20= 80) → write( 80)   ← T1's update lost
```

Other anomalies that the platform guards against:

| Anomaly                | Description |
|------------------------|-------------|
| **Lost update**        | Second writer silently overwrites first writer's committed change |
| **Write skew**         | Two transactions each read stale state and derive inconsistent conclusions |
| **Phantom read**       | A re-read within a transaction sees new rows inserted concurrently |
| **Double charge**      | Concurrent confirm calls each believe they are first to initiate payment |
| **Sequence gap**       | Two threads both read the same counter and emit duplicate sequence numbers |

The right concurrency control depends on the operation, the expected traffic volume,
and the tolerance for false conflicts (cancelled, retried work).

---

## 2. Strategies in use

### 2.1 Optimistic Concurrency Control (OCC)

**Mechanism:** Every entity carries a `@Version` column incremented by Hibernate on
every `UPDATE`.  Hibernate adds `WHERE version = :expectedVersion` to every `UPDATE`,
and throws `ObjectOptimisticLockingFailureException` if zero rows are affected (meaning
another transaction committed first).

**Best for:** High-read, low-write-contention operations — subscription state
transitions, payment intent field updates.

#### Why blind immediate retry is wrong

```
T1 fails with OCC conflict at version 5.
T1 immediately retries still holding the in-memory entity at version 5.
T1 fails again with the same conflict.  Ad infinitum.
```

The entity **must be re-read** from the database before each retry.  Retrying with the
same stale version in memory will always conflict because Hibernate compares the
in-memory version field against the current DB row.

#### Thundering herd

When many threads fail simultaneously they should not all retry at the same instant —
that creates a spike of write contention.  `RetryJitterStrategy` adds uniform random
noise to the back-off delay, spreading retries over a small time window.

#### `OptimisticRetryTemplate` contract

`OptimisticRetryTemplate` enforces the reload contract: the caller's `Supplier<T>` is
invoked fresh on **every** attempt.  A supplier must re-fetch the entity from the
repository rather than capturing a stale instance in a closure.

```java
// ✗ WRONG — stale entity captured in closure
Subscription sub = repo.findById(id).orElseThrow();
guardService.withOptimisticRetry(() -> {
    sub.cancel();            // sub.version is ALWAYS stale after first conflict
    return repo.save(sub);
}, "subscription.cancel");

// ✓ CORRECT — entity reloaded inside supplier
guardService.withOptimisticRetry(() -> {
    Subscription fresh = repo.findById(id).orElseThrow(); // re-fetched each attempt
    fresh.cancel();
    return repo.save(fresh);
}, "subscription.cancel");
```

**Default policy** (via `RetryBackoffPolicy.defaultPolicy()`): up to 5 retries,
50 ms base delay, doubling on each attempt, 30% jitter.

---

### 2.2 Pessimistic locking (`SELECT ... FOR UPDATE`)

**Mechanism:** `EntityManager.find(..., LockModeType.PESSIMISTIC_WRITE)` or a Spring
Data `@Lock(PESSIMISTIC_WRITE)` repository method issues `SELECT ... FOR UPDATE`,
blocking other transactions on the same row until the current transaction commits or
rolls back.

**Best for:** Operations that perform a read-then-write where the decision depends on
values that must not change between the read and the write:

- Cumulative refund ceiling check (refund.create)
- Fund reservation for disputes (dispute.open)
- Monotonic invoice-sequence increment (invoice_sequence.increment)

**Pitfall:** Holding a pessimistic lock across an external API call (e.g. PSP request)
can cause database lock timeouts in other transactions.  Keep locked transactions short.

**`PessimisticLockHelper`** is a thin wrapper over `EntityManager.find` that makes the
lock intent self-documenting at the call site.

---

### 2.3 `SELECT ... FOR UPDATE SKIP LOCKED`

**Mechanism:** An extension to pessimistic locking.  Rows already locked by another
transaction are silently skipped rather than causing the current transaction to block.

**Best for:** Work-queue polling (outbox events, retry queues).  Multiple workers can
poll concurrently without blocking each other, each picking up a disjoint set of rows.

---

### 2.4 SERIALIZABLE isolation

**Mechanism:** The database serialises all concurrent transactions as if they executed
one after another.  This prevents phantom reads and write skew without explicit
row-level locks.

**Best for:** Balance checks or constraint validations that span multiple rows and must
be logically consistent (e.g. "the sum of all posted amounts equals the invoice total").

**Cost:** The highest isolation level; may cause more aborts (serialisation failures)
under high concurrency.  Use sparingly.

**`SerializableTxnExecutor`** wraps any `Supplier<T>` in a `@Transactional` method at
`SERIALIZABLE` isolation.  Because Spring AOP is involved, the method must be called
through the injected Spring bean — not on a directly-constructed instance.

---

### 2.5 Distributed fence token (planned)

**Mechanism:** A distributed lock (e.g. Redis Redlock, Redisson) issues a monotonically
increasing **fence token** when a lock is acquired.  Any write to durable storage must
present this token.  The storage layer rejects writes with a stale token even if the
writer believes it still owns the lock.

**Why OCC alone is insufficient for `subscription.renewal`:**

Renewal involves:
1. Acquiring a payment authorisation from an external PSP.
2. Writing the payment result back to the database.

Between steps 1 and 2, the JVM might pause (GC, network partition), the lock might
expire, and a second worker could start step 1 for the same subscription.  OCC would
only detect the conflict at step 2 — after the customer has already been double-charged.
A fence token makes step 1 itself safe by ensuring the external call is only executed
while the fence is valid.

**`FenceAwareUpdater<T>`** defines the interface that entities must implement to
participate in this pattern.  Distributed lock integration is deferred to a future phase.

---

### 2.6 Advisory / scheduler-singleton lock

**Mechanism:** A row in the `job_locks` table (see `JobLock`, `JobLockService`) acts as
a named advisory lock.  Only the holder of the row's lock column can run the scheduled
job.

**Best for:** Scheduled jobs that must run on exactly one application instance in a
horizontally-scaled cluster.

---

### 2.7 Idempotent async (no strong lock)

**Mechanism:** The operation is designed to be safely re-applied.  The result is the
same regardless of how many times it runs, so no locking is needed.

**Best for:** Read-side projection rebuilds, snapshot regeneration.

---

## 3. Decision catalog

`LockingDecisionCatalog` encodes the recommended strategy for every major domain
write operation:

| Domain operation              | Strategy                | Rationale summary |
|-------------------------------|-------------------------|-------------------|
| `subscription.renewal`        | `DISTRIBUTED_FENCE`     | Multi-step external payment; double-charge risk exceeds OCC guarantees |
| `refund.create`               | `PESSIMISTIC_FOR_UPDATE`| Cumulative-refund ceiling check must see the latest committed total |
| `dispute.open`                | `PESSIMISTIC_FOR_UPDATE`| Fund reservation + state transition must be atomic |
| `invoice_sequence.increment`  | `PESSIMISTIC_FOR_UPDATE`| Monotonic sequence with no gaps |
| `outbox.poll`                 | `SKIP_LOCKED`           | Work-queue distribution without worker contention |
| `scheduler.singleton`         | `ADVISORY`              | Cluster-wide singleton job execution |
| `projection.update`           | `IDEMPOTENT_ASYNC`      | Safe to re-apply; eventual consistency acceptable |

To look up the decision programmatically:

```java
LockingDecisionCatalog catalog = …;
catalog.forOperation("refund.create")
       .ifPresent(d -> log.info("strategy={} rationale={}", d.strategy(), d.rationale()));
```

---

## 4. Entity-reload requirement for OCC retries

The most common mistake when setting up OCC retry loops is capturing a stale entity in
the supplier closure (see [§ 2.1](#21-optimistic-concurrency-control-occ) for the full
explanation with code examples).

A useful mental model: treat each `Supplier<T>` passed to `OptimisticRetryTemplate` as
an _idempotent database operation_ that must read its inputs fresh on every call.

---

## 5. Class map

```
com.firstclub.platform.concurrency
├── ConcurrencyGuard               — static helpers: OCC translation (Phase 9)
├── ConcurrencyConflictException   — domain exception for all conflict types → HTTP 409
├── BusinessLockScope              — enum registry of every named lock scope
└── ConcurrencyGuardService        — facade (Phase 5): composes retry + serializable + catalog

com.firstclub.platform.concurrency.retry
├── RetryJitterStrategy            — uniform random jitter: prevents thundering-herd
├── RetryBackoffPolicy             — immutable record: maxRetries, baseDelayMs, multiplier, jitter
└── OptimisticRetryTemplate        — exponential-backoff retry loop for OCC exceptions

com.firstclub.platform.concurrency.locking
├── LockingStrategy                — enum: OPTIMISTIC, DISTRIBUTED_FENCE, ADVISORY, …
├── LockingDecision                — record: (domainOperation, strategy, rationale)
├── LockingDecisionCatalog         — 7-entry authoritative strategy catalog (Spring bean)
├── PessimisticLockHelper          — EntityManager.find(..., PESSIMISTIC_WRITE) wrapper
├── SerializableTxnExecutor        — @Transactional(isolation=SERIALIZABLE) wrapper
└── FenceAwareUpdater<T>           — interface for fence-token-aware entity updates
```

---

## 6. Adding a new concurrency guard

1. Identify the anomaly you are preventing (see § 1).
2. Choose the appropriate strategy (see § 2).
3. Add an entry to `LockingDecisionCatalog` with the operation name and rationale.
4. Add a constant to `BusinessLockScope` documenting the invariant and failure mode.
5. Use `ConcurrencyGuardService` at the call site:
   - `withOptimisticRetry(...)` — OCC with automatic retry
   - `withSerializable(...)` — SERIALIZABLE isolation
   - `withPessimisticLock(...)` — documents intent; use `PessimisticLockHelper` inside the supplier
6. Write a unit test demonstrating both the happy path and the conflict case.
