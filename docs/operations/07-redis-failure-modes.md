# Redis Failure Modes — Operations Reference

**Status:** Canonical  
**Last updated:** Phase 2 — Redis Foundation  
**Applies to:** All environments

---

## 1. Core Principle

> **Rejecting a payment because Redis is down is an availability failure.**

Redis is an optional acceleration layer. A Redis outage must never surface as a customer-visible error for any operation that has a valid DB fallback. Downgrade, do not fail.

---

## 2. Error Classification (`RedisErrorClassification`)

Every exception thrown by Lettuce or Spring Data Redis is classified into one of three categories before any decision is made.

| Category | Constant | Trigger exceptions | Meaning |
|---|---|---|---|
| **TRANSIENT** | `RedisErrorClassification.TRANSIENT` | `QueryTimeoutException`, unknown `RuntimeException` | The operation timed out or failed for an unknown reason. Redis may still be up. Retry is safe. |
| **UNAVAILABLE** | `RedisErrorClassification.UNAVAILABLE` | `RedisConnectionFailureException`, `RedisSystemException` with connect/refused/unreachable in message | Redis cluster is unreachable. Failover to DB is required. |
| **SERIALIZATION** | `RedisErrorClassification.SERIALIZATION` | `SerializationException` | Data could not be serialised/deserialised. Redis is up, but the value is unusable. |

### Classification algorithm

```
classify(ex):
  if ex is SerializationException       → SERIALIZATION
  if ex is RedisConnectionFailureException → UNAVAILABLE
  if ex is QueryTimeoutException           → TRANSIENT
  if ex is RedisSystemException:
    if message contains "connect|refused|unreachable" → UNAVAILABLE
    else                                              → TRANSIENT
  default                                → TRANSIENT  (conservative)
```

The conservative default (unknown = TRANSIENT) is intentional. Unknown failures are treated as retriable rather than fatal, preserving availability.

---

## 3. Failure Behavior Policy (`RedisFailureBehavior`)

Callers declare their failure behavior at the call site. This is not configured globally — the right behavior depends on the semantics of the key being accessed.

| Behavior | Constant | When to use |
|---|---|---|
| **ALLOW** | `RedisFailureBehavior.ALLOW` | Redis is best-effort; the caller has a full DB fallback. Logs a warning and continues normally. |
| **REJECT** | `RedisFailureBehavior.REJECT` | Redis is required for correctness (rare). Propagates the exception to the caller. |
| **DEGRADE_TO_DB** | `RedisFailureBehavior.DEGRADE_TO_DB` | Redis is a DB read optimisation. Explicitly routes to the database on failure. |

### Assignment guidelines

| Use case | Recommended behavior | Reason |
|---|---|---|
| Idempotency key check (read) | `DEGRADE_TO_DB` | Fall back to `idempotency_keys` table |
| Idempotency lock (SETNX) | `ALLOW` | DB UNIQUE constraint catches duplicates |
| Rate limit counter | `ALLOW` | Lose a count; don't block the request |
| Distributed lock (`SETNX`) | `ALLOW` | Fall back to `SELECT FOR UPDATE` |
| Worker outbox lease | `ALLOW` | Lease table column handles double-delivery |
| Projection KPI cache (read) | `DEGRADE_TO_DB` | Re-query the projection table |
| Gateway health check | `ALLOW` | Stale or missing = assume healthy |
| Feature flag read | `DEGRADE_TO_DB` | Fall back to DB flag table |
| Routing rules cache | `DEGRADE_TO_DB` | Re-query routing rules table |

> `REJECT` should be used only when the caller's correctness contract explicitly requires Redis — in Phase 1–2 scope, this is limited to security/fraud workflows where a missing rate limit would be actively harmful. Even then, prefer `ALLOW` with compensating controls.

---

## 4. `RedisOpsFacade` Behaviour on Failure

Every method in `RedisOpsFacade` catches `RuntimeException`, classifies it, logs it at WARN level, and returns a safe default. No method propagates an exception.

| Method | Safe default on failure | Notes |
|---|---|---|
| `setIfAbsent(key, value, ttl)` | `false` | Caller should treat as "key already present" |
| `set(key, value, ttl)` | (no return value) | Side effect silently lost |
| `get(key)` | `Optional.empty()` | Caller triggers DB fallback |
| `delete(key)` | `false` | Key may still exist; DB is source of truth |
| `increment(key)` | `-1L` | Sentinel: caller must detect and fall back |
| `expire(key, ttl)` | `false` | TTL not extended; key will expire naturally |
| `executePipelined(callback)` | empty `List` | All pipeline results lost |

---

## 5. Operator Runbook

### 5.1 Redis reports DEGRADED

**Symptom:** `GET /api/v2/admin/system/redis/health` returns `status: "DEGRADED"` (high latency, partial connectivity).

**Steps:**
1. Check Redis memory: `INFO memory` — look for `used_memory_rss_human` and `mem_fragmentation_ratio`.
2. Check slow-log: `SLOWLOG GET 25`.
3. Review connection pool size in `application.properties`: `app.redis.connect-timeout-ms` / `app.redis.command-timeout-ms`.
4. Application continues to serve traffic — `ALLOW` and `DEGRADE_TO_DB` paths are active.
5. Raise an alert; do not page unless error rate in dependent APIs rises.

### 5.2 Redis reports DOWN / health endpoint returns 503

**Symptom:** Application logs `RedisErrorClassification=UNAVAILABLE` at high rate; health endpoint returns `status: "DOWN"`.

**Steps:**
1. Verify Redis container/service is running.
2. Check network connectivity from app server to Redis host.
3. **Application degradation is automatic** — all `RedisOpsFacade` calls return safe defaults.
4. Monitor DB connection pool: load shifts to PostgreSQL during Redis outage.
5. Once Redis is restored, no application restart is required — Lettuce reconnects automatically.
6. Verify recovery: `GET /api/v2/admin/system/redis/health` should return `status: "UP"`.

### 5.3 Redis reports DISABLED

**Symptom:** `app.redis.enabled=false` (e.g., local development without Docker).

**Behaviour:** `DisabledRedisAvailabilityService` is active. All cache/lock features are bypassed. The application uses DB paths exclusively. This is the expected behaviour in local development environments.

### 5.4 High `SERIALIZATION` error rate

**Symptom:** Logs show `RedisErrorClassification=SERIALIZATION` on `get()` calls.

**Cause:** Typically a schema change to a Java DTO class serialised as JSON and cached in Redis. Old JSON in Redis cannot be deserialised into the new class.

**Steps:**
1. Identify the affected key namespace from the log.
2. Flush the affected keyspace: `UNLINK {env}:firstclub:{domain}:*` (use `UNLINK`, not `DEL` — it is non-blocking).
3. Deploys with DTO changes must increment the domain's cache key or flush stale keys as part of the release procedure.

---

## 6. Logging Convention

All Redis failure logs follow this format:

```
WARN  RedisOpsFacade - Redis operation failed [op=get, key=prod:firstclub:idem:resp:42:abc123, classification=UNAVAILABLE]: Connection refused
```

Log fields:
- `op` — the operation name (`set`, `get`, `delete`, `increment`, `expire`, `pipeline`)
- `key` — full Redis key (may contain merchant ID; do not log values)
- `classification` — `TRANSIENT` / `UNAVAILABLE` / `SERIALIZATION`

Do not log Redis key **values** — they may contain session tokens or PII.

---

## 7. Monitoring & Alerting

| Signal | Threshold | Action |
|---|---|---|
| Redis health = DEGRADED | > 5 min | Investigate; notify on-call |
| Redis health = DOWN | Any | Page on-call; check DB pool pressure |
| `UNAVAILABLE` classification rate | > 10/sec | Page on-call |
| `SERIALIZATION` classification rate | > 5/min | Investigate DTO schema mismatch |
| DB query P99 spike > 2× baseline | During Redis DOWN | Expected; verify it subsides on recovery |

Metrics are exposed via Spring Boot Actuator / Micrometer at `/actuator/metrics`.

---

## 8. Future Improvements (Out of Scope for Phase 2)

- **Circuit breaker:** Wrap `RedisOpsFacade` with Resilience4j `CircuitBreaker`. When UNAVAILABLE count exceeds threshold, open the circuit for 60 s and skip Redis entirely during the open window.
- **Redis Sentinel / Cluster:** Lettuce supports both. Required before handling > 1 M RPM.
- **Key-level metrics:** Tag Micrometer counters with domain and operation type to build per-domain Redis hit/miss dashboards.
- **Automated key flush on deploy:** CI pipeline step to flush changed-schema keyspaces.
