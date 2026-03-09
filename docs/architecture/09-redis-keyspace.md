# Redis Keyspace — Architecture Reference

**Status:** Canonical  
**Last updated:** Phase 2 — Redis Foundation  
**Applies to:** All environments (`dev`, `local`, `staging`, `prod`)

---

## 1. Guiding Principle

> **Redis is never the source of truth for financial state.**

Every key documented here is a performance optimisation layer over PostgreSQL.
No Redis key holds data that cannot be reconstructed from the database.
Every caller that reads from Redis **must** implement a DB fallback.

---

## 2. Key Format

All platform Redis keys follow a canonical 5-segment format:

```
{env}:firstclub:{domain}:{subdomain}:{identifier...}
```

| Segment | Source | Example |
|---|---|---|
| `{env}` | Active Spring profile | `prod`, `staging`, `dev` |
| `firstclub` | Fixed application identifier (`RedisNamespaces.APP`) | `firstclub` |
| `{domain}` | Functional domain (`RedisNamespaces.*`) | `idem`, `rl`, `lock` |
| `{subdomain}` | Sub-context within domain | `resp`, `lock`, `endpoint` |
| `{identifier...}` | One or more entity identifiers | `merchantId:keyHash` |

### Design rationale

- The `firstclub` fixed segment prevents key collisions when multiple applications share a Redis cluster.
- The `{env}` prefix allows prod and staging to share a cluster safely (though they should not in practice).
- No segment may contain `:` — colons are reserved as separators.
- All segments are lowercase. Exceptions: `GATEWAY` names are uppercased (see `RedisKeyFactory.gatewayHealthKey`).

---

## 3. Domain Catalogue

| Namespace constant | Segment | Phase | Primary use |
|---|---|---|---|
| `IDEMPOTENCY` | `idem` | Phase 2 | Response cache + in-flight lock |
| `RATE_LIMIT` | `rl` | Phase 2 | Per-merchant/IP counters |
| `GATEWAY` | `gw` | Phase 3 | Gateway health markers |
| `ROUTING` | `routing` | Phase 4 | Routing rules cache |
| `MERCHANT` | `merchant` | Phase 4 | Merchant settings cache |
| `FEATURE_FLAG` | `flag` | Phase 5 | Feature flag values |
| `PROJECTION` | `proj` | Phase 6 | Read-model lag markers |
| `DEDUP` | `dedup` | Phase 7 | Fingerprint deduplication |
| `OUTBOX` | `outbox` | Phase 7 | Event processing markers |
| `SCHEDULER` | `scheduler` | Phase 7 | Distributed job locks |
| `LOCK` | `lock` | Phase 2 | Generic entity-scoped distributed locks |
| `FENCE` | `fence` | Phase 2 | Optimistic concurrency fence tokens |
| `WORKER` | `worker` | Phase 2 | Async worker lease markers |
| `CACHE` | `cache` | Phase 2 | Hot projection/KPI result cache |
| `TIMELINE` | `timeline` | Phase 8 | Subscription/payment timeline cache |
| `SUBSCRIPTION` | `sub` | Phase 9 | Subscription state cache |
| `PAYMENT` | `payment` | Phase 10 | Payment capture locks |
| `LEDGER` | `ledger` | Phase 11 | Account balance snapshot cache |
| `RECON` | `recon` | Phase 12 | Reconciliation result + job lock |
| `SEARCH` | `search` | Phase 13 | Unified admin search cache |

---

## 4. Key Examples with TTLs and Fallbacks

### 4.1 Idempotency

| Key pattern | Example | TTL | Fallback |
|---|---|---|---|
| `{env}:firstclub:idem:resp:{merchantId}:{keyHash}` | `prod:firstclub:idem:resp:42:abc123` | 24 h | `idempotency_keys` table |
| `{env}:firstclub:idem:lock:{merchantId}:{keyHash}` | `prod:firstclub:idem:lock:42:abc123` | 30 s | `idempotency_keys` UNIQUE constraint |

### 4.2 Rate Limiting

| Key pattern | Example | TTL | Fallback |
|---|---|---|---|
| `{env}:firstclub:rl:apikey:{merchantId}:{prefix}:{window}` | `prod:firstclub:rl:apikey:42:abc12345:1m` | 60 s | Per-JVM counter |
| `{env}:firstclub:rl:{merchantId}:endpoint:{slug}` | `prod:firstclub:rl:42:endpoint:payment_capture` | 60 s | Per-JVM counter |
| `{env}:firstclub:rl:auth:ip:{ip}` | `prod:firstclub:rl:auth:ip:1.2.3.4` | 60 s | None (best-effort) |

### 4.3 Distributed Locks

| Key pattern | Example | TTL | Fallback |
|---|---|---|---|
| `{env}:firstclub:lock:{entityType}:{entityId}` | `prod:firstclub:lock:subscription:991` | 30 s | `SELECT FOR UPDATE` |
| `{env}:firstclub:scheduler:lock:{jobName}` | `prod:firstclub:scheduler:lock:dunning_daily` | 3600 s | `job_locks` table |

### 4.4 Optimistic Concurrency

| Key pattern | Example | TTL | Fallback |
|---|---|---|---|
| `{env}:firstclub:fence:{entityType}:{entityId}` | `prod:firstclub:fence:subscription:991` | None | `version` column (Hibernate OL) |

### 4.5 Worker Leases

| Key pattern | Example | TTL | Fallback |
|---|---|---|---|
| `{env}:firstclub:worker:outbox:lease:event:{eventId}` | `prod:firstclub:worker:outbox:lease:event:12345` | 60 s | `outbox_events.locked_until` |

### 4.6 Projection Hot-Cache

| Key pattern | Example | TTL | Fallback |
|---|---|---|---|
| `{env}:firstclub:cache:projection:{merchantId}:kpi:{date}` | `prod:firstclub:cache:projection:42:kpi:2026-03-09` | 10 m | Live aggregate from `subscription_status_projection` |
| `{env}:firstclub:proj:sub-status:{merchantId}:{subId}` | `prod:firstclub:proj:sub-status:42:sub-001` | 120 s | `subscription_status_projection` table |

---

## 5. TTL Philosophy

TTLs are configured via `RedisTtlConfig` (`app.redis.ttl.*` namespace).

| Rule | Rationale |
|---|---|
| Every key MUST have a TTL | Unbounded keys accumulate and cause OOM events |
| TTL is domain-specific | Different domains have different staleness tolerances |
| Default TTL = 5 min (`app.redis.default-ttl-seconds=300`) | Safety net for keys that miss their domain TTL |
| Never set TTL=0 or negative | Zero = immediate expiry; negative = no expiry |
| Locks have short TTLs (≤60 s) | Abandoned locks must not block forever |
| Caches have longer TTLs (5–60 min) | Minimises DB pressure on high-traffic paths |

### Default TTL values (override in `application-prod.properties`):

```properties
app.redis.ttl.idempotency-lock=PT30S
app.redis.ttl.idempotency-record=PT24H
app.redis.ttl.rate-limit-window=PT60S
app.redis.ttl.routing-cache=PT5M
app.redis.ttl.projection-cache=PT10M
app.redis.ttl.distributed-lock=PT30S
app.redis.ttl.search-cache=PT5M
app.redis.ttl.worker-lease=PT60S
```

---

## 6. Factory Usage

All key strings are generated by `RedisKeyFactory`. Never construct Redis key strings manually in business code.

```java
// Correct
String key = redisKeyFactory.distributedLockKey("subscription", subscriptionId);

// WRONG — brittle, error-prone, invisible to namespace audit
String key = "prod:firstclub:lock:subscription:" + subscriptionId;
```

---

## 7. Future Domain Reservations

The following namespaces are reserved for future phases and must not be used for other purposes:

| Namespace | Reserved for |
|---|---|
| `RISK` | IP-block and velocity counters (Phase 8) |
| `WEBHOOK` | Delivery locks and endpoint disable markers (Phase 9) |
| `REFUND` | Per-payment refund locks (Phase 10) |
| `DISPUTE` | Per-payment dispute locks (Phase 10) |

---

## 8. Known Limits

- **Single-node Redis only.** This platform does not use Redis Cluster or Sentinel. Scaling beyond a single instance requires the routing layer to be updated to key-consistent hashing.
- **No Redis transactions in Phase 2.** `MULTI/EXEC` is available via `StringRedisTemplate` but is not used. All operations are single-command or pipelined.
- **No persistence.** Redis is configured without AOF or RDB persistence by default. A Redis restart clears all keys — this is intentional. All data lives in PostgreSQL.
- **No cross-environment data isolation by cluster.** The `{env}` key prefix prevents collisions, but a shared Redis cluster between prod and staging is a security risk. Use separate clusters in production.
