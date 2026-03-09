# Distributed Locking and Fencing Tokens

**Platform layer:** `com.firstclub.platform.lock`  
**Status:** Production-ready  
**Related docs:** [06-concurrency-model.md](06-concurrency-model.md) | [07-concurrency-strategy.md](07-concurrency-strategy.md) | [09-redis-keyspace.md](09-redis-keyspace.md)

---

## Why we need distributed locks

The platform runs multiple JVM processes (pods) that share a PostgreSQL database and Redis.
Several operations are logically single-writer:

| Operation | Why it must not run concurrently |
|---|---|
| Subscription renewal attempt | Double-charge if two pods both attempt billing |
| Dunning state machine advance | Conflicting state transitions lead to inconsistent invoice status |
| Refund decision | Duplicate refund trigger if two threads both pass the balance check |
| Scheduled dunning job | Scheduler fires once per pod; only one may process a given billing cycle |

JVM-level locks (`synchronized`, `ReentrantLock`) are invisible to other pods.
Optimistic locking (Phase 5) handles *update* conflicts but cannot coordinate *decision* conflicts where two threads race through the same decision gate before either writes.

Distributed locks fill the gap: cross-pod, cross-thread mutual exclusion for a bounded critical section.

---

## Why simple `DEL lockKey` is unsafe

```
Time 0: Thread A acquires lock (value="owner-A", TTL=30s)
Time 1: Thread A experiences GC pause lasting 35 seconds
Time 2: Lock TTL expires — key is deleted by Redis automatically
Time 3: Thread B acquires lock (value="owner-B", TTL=30s)
Time 4: Thread A wakes up and calls DEL lockKey
         ↳ Thread A just deleted Thread B's lock!
Time 5: Thread C also acquires the lock — B and C now overlap.
```

The correct primitive is: **check ownership, then delete, atomically**.
Redis Lua scripts run atomically and make this possible.

---

## Lua scripts

All three lifecycle operations are implemented as atomic Lua scripts in `LockScriptRegistry`.

### ACQUIRE

```lua
if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', tonumber(ARGV[2])) then
  return 1   -- acquired
else
  return 0   -- already locked
end
```

`SET NX PX` is atomic: the key is set only if it does not exist, and the TTL is applied in
the same command.  No window exists between setting the value and setting the TTL.

### EXTEND

```lua
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]))
else
  return 0   -- owner mismatch — lock expired and was re-acquired by a different owner
end
```

Called by `LockLeaseHeartbeat` periodically to prevent TTL expiry during long operations.
Returns `0` if ownership has been lost (expired and then taken by someone else), allowing
the heartbeat to self-cancel and the caller to discover the loss on its next fence token check.

### RELEASE

```lua
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
else
  return 0   -- owner mismatch — must NOT delete another holder's lock
end
```

The owner token (`{instanceId}:{threadId}:{uuid}`) is unique per acquisition attempt,
so expiry + re-acquisition by a different thread always produces a different value causing
`== ARGV[1]` to false correctly.

---

## Fencing tokens — why Redis locks alone are insufficient

Even a correctly-implemented Lua-based Redis lock is **not linearizable**.
Consider this scenario:

```
Time 0:  Thread A acquires lock; fenceToken = 33
Time 1:  Thread A calls service.processRenewal()
Time 2:  Thread A experiences a network partition (pod temporarily isolated)
Time 3:  Lock TTL expires
Time 4:  Thread B acquires lock; fenceToken = 34
Time 5:  Thread B writes subscription.status = ACTIVE with last_fence_token = 34
Time 6:  Network partition heals; Thread A resumes
Time 7:  Thread A tries to write subscription.status = ACTIVE with last_fence_token = 33
         ↳ Without a token check — Thread A's write succeeds and corrupts Thread B's state!
         ↳ With a token check — Thread A's write is REJECTED (33 < 34) → StaleOperationException
```

### How the platform enforces fencing

1. **Token generation:** `FencingTokenService.generateToken(resourceType, resourceId)` calls Redis
   `INCR` on key `{env}:firstclub:fence:{type}:{id}`. Tokens are monotonically increasing across
   all acquisitions of the same resource.

2. **Token storage:** After acquiring the lock, `DistributedLockService` generates a fence token
   and attaches it to the `LockHandle`. The token is also stored in the entity's `last_fence_token`
   column on each DB write.

3. **Token enforcement (write path):**
   ```java
   @Transactional
   public void renewSubscription(Long subId, long fenceToken) {
       Subscription sub = repo.findWithPessimisticLock(subId);
       fencingTokenService.enforceTokenValidity(
           "Subscription", subId, fenceToken, sub.getLastFenceToken());
       // ^ throws StaleOperationException (HTTP 409) if fenceToken < sub.getLastFenceToken()
       sub.setLastFenceToken(fenceToken);
       sub.setStatus(ACTIVE);
       repo.save(sub);
   }
   ```

4. **Token column in schema (V52 migration):**
   ```sql
   ALTER TABLE subscriptions_v2     ADD COLUMN IF NOT EXISTS last_fence_token BIGINT NOT NULL DEFAULT 0;
   ALTER TABLE payment_intents_v2   ADD COLUMN IF NOT EXISTS last_fence_token BIGINT NOT NULL DEFAULT 0;
   ALTER TABLE invoices              ADD COLUMN IF NOT EXISTS last_fence_token BIGINT NOT NULL DEFAULT 0;
   ```

---

## Key schema

All Redis keys follow the platform namespacing rules (see `09-redis-keyspace.md`):

| Purpose | Pattern | Example |
|---|---|---|
| Lock value | `{env}:firstclub:lock:{type}:{id}` | `prod:firstclub:lock:subscription:42` |
| Fence counter | `{env}:firstclub:fence:{type}:{id}` | `prod:firstclub:fence:subscription:42` |

The lock value is the **owner token** (`{instanceId}:{threadId}:{uuid}`).  
The fence key holds a monotonically increasing integer (Redis `INCR` counter, never reset).

---

## Owner identity

`LockOwnerIdentityProvider` generates a token that is unique across:

| Dimension | Component | Example |
|---|---|---|
| Node | `hostname-{8-char UUID prefix}` | `pod-7c9f-a1b2c3d4` |
| Thread | JVM thread ID | `42` |
| Acquisition | random UUID | `550e8400-e29b-41d4-a716-446655440000` |

Full example: `pod-7c9f-a1b2c3d4:42:550e8400-e29b-41d4-a716-446655440000`

This ensures no two acquisition attempts — even on the same pod, same thread — produce the
same owner token.

---

## Lease heartbeat — handling long operations

A lock's TTL is set conservatively (default: 30 s).  If the critical section might run longer
(bulk DB update, external API call), register a heartbeat:

```java
try (LockHandle lock = lockService.acquireWithRetry("invoice-batch", jobId, ttl, timeout)) {
    heartbeat.register(lock, Duration.ofSeconds(10), ttl);   // renew every 10 s
    performBulkUpdate();
    // heartbeat auto-cancels when lock.close() is called by try-with-resources
}
```

`LockLeaseHeartbeat` runs a single daemon thread (`lock-lease-heartbeat`) and keeps a
`ConcurrentHashMap` of active `ScheduledFuture<?>`s per `resourceType:resourceId:lockOwner`.

The heartbeat self-cancels when:
- `handle.isReleased()` returns `true` (normal exit from try-with-resources)
- `extend()` returns `false` (lock expired and was taken by another process)

---

## Advisory locks vs. distributed locks

| Dimension | `DistributedLockService` (Redis) | `AdvisoryLockRepository` (PostgreSQL) |
|---|---|---|
| Scope | Cross-service, cross-datacenter | Single PostgreSQL session |
| Availability | Redis must be reachable | Embedded in DB connection |
| Persistence | TTL-bounded; survives pod restart | Released on session close |
| Suitability | Subscription renewal, payment processing | Scheduler singletons, migration jobs |
| H2 compatible | Yes (Redis mock / disabled) | **No** — `pg_advisory_lock` is Postgres-only |

`AdvisoryLockRepository` is a plain Java interface that documents the PostgreSQL advisory lock
pattern.  Implementations are environment-specific and must not run in H2-backed test profiles.

---

## Decision flow: which lock type to use

```
Need mutual exclusion across pods?
  YES → Use DistributedLockService (Redis)
    Operation might outlast a short TTL?
      YES → Register a LockLeaseHeartbeat
    Write path needs to survive a GC pause / network partition?
      YES → Enforce fence token in DB write (FencingTokenService.enforceTokenValidity)
    
  NO (single pod, single DB session) →
    Is it a scheduler singleton job?
      YES → Use AdvisoryLockRepository (PostgreSQL advisory lock)
    Is it a JPA entity update?
      YES → Use @Version / OptimisticRetryTemplate (Phase 5)
      NO  → Use LockingStrategy.PESSIMISTIC (@Lock(PESSIMISTIC_WRITE))
```

---

## Error handling and observability

| Event | Log prefix | Level | Action |
|---|---|---|---|
| Lock acquired | `[LOCK-ACQUIRED]` | INFO | Log resource, fenceToken, elapsedMs |
| Lock released | `[LOCK-RELEASED]` | INFO | Log resource, ownLock flag |
| Lease extended | `[LOCK-EXTENDED]` | INFO | Log resource |
| Extension failed | `[LOCK-EXTEND-FAILED]` | WARN | Heartbeat cancels itself |
| Timeout | `[LOCK-TIMEOUT]` | WARN | Throws `LockAcquisitionTimeoutException` |
| Redis unavailable | `[LOCK-FAILURE]` | WARN | Returns `FAILED_REDIS_UNAVAILABLE` |
| Fence rejected | `[FENCE-REJECTED]` | WARN | Throws `StaleOperationException` (HTTP 409) |

All log lines include MDC context with `lock.resource` and (for acquisitions) `lock.fenceToken`.

---

## Package structure

```
com.firstclub.platform.lock/
  LockHandle.java                  — AutoCloseable handle; carries fence token
  LockAcquisitionResult.java       — Status enum + optional handle
  LockAcquisitionTimeoutException  — Thrown when retry window exhausted
  DistributedLockService.java      — Primary service: tryAcquire / acquireWithRetry / extend
  LockLeaseHeartbeat.java          — Heartbeat component for long operations
  AdvisoryLockRepository.java      — Plain interface (DB advisory lock contract/docs)

  redis/
    LockScriptRegistry.java        — Lua ACQUIRE / EXTEND / RELEASE scripts
    LockOwnerIdentityProvider.java — hostname:threadId:uuid token generation

  fencing/
    FencingTokenService.java       — Redis INCR token generation + DB enforcement

  metrics/
    LockMetricsService.java        — MDC-enriched structured logging
```
