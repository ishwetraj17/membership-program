# Redis Usage

**Phase 2 status: Infrastructure deployed, disabled by default.**

The `platform.redis` package provides a complete, production-ready Redis foundation:
- `RedisConfig` — conditional Lettuce connection factory and templates
- `RedisKeyFactory` — typed key builder for all namespaced key patterns
- `RedisJsonCodec` — Jackson-backed serialise/deserialise wrapper
- `RedisAvailabilityService` — live PING check + no-op fallback when disabled
- `RedisHealthIndicator` — Spring Boot Actuator health contributor
- Admin endpoint: `GET /api/v2/admin/system/redis/health`

**To activate Redis:** Set `app.redis.enabled=true` and provide `app.redis.host` / `app.redis.port`.

**Current state when disabled (default):** All paths fall through to PostgreSQL.
When `app.redis.enabled=false`, `DisabledRedisAvailabilityService` returns `RedisStatus.DISABLED`
and no connection is attempted. The deep health report shows `redisStatus: "DISABLED"`.

**Redis is never the source of truth for financial data.** Every caller must implement a
PostgreSQL fallback that works correctly when Redis is absent or slow.

---

## Deployment Configuration

```properties
# Enable Redis infrastructure
app.redis.enabled=true
app.redis.host=your-redis-host
app.redis.port=6379
app.redis.password=${REDIS_PASSWORD}  # supply via env var in production
app.redis.ssl=true                    # always use TLS in production
app.redis.database=0
app.redis.key-prefix=fc
app.redis.default-ttl-seconds=300
app.redis.connect-timeout-ms=2000
app.redis.command-timeout-ms=500
```

Redis auto-configuration is excluded from Spring's auto-wiring
(`spring.autoconfigure.exclude` in `application.properties`).
`RedisConfig` takes full ownership and creates beans only when enabled.

---

## Key Naming Convention

All keys follow:
```
{env}:firstclub:{domain}:{operation}:{...identifiers}
```

| Segment | Values |
|---|---|
| `{env}` | `prod`, `staging`, `test` |
| `{domain}` | `idem`, `sub`, `payment`, `recon`, `rl`, `outbox`, `webhook`, `ledger`, `risk` |

---

## Key Catalogue

### Idempotency

#### Response Cache
```
{env}:firstclub:idem:resp:{merchantId}:{idempotencyKey}
```
- **Type:** String
- **TTL:** 86400s (24h, configurable)
- **Value:** `{statusCode}|{responseBodyJson}`
- **Set by:** Idempotency filter, after persisting to DB, on first successful execution
- **Read by:** Idempotency filter on every mutating request
- **Fallback:** DB lookup in `idempotency_keys` table
- **Invalidation:** Natural TTL expiry

#### In-Flight Lock
```
{env}:firstclub:idem:lock:{merchantId}:{idempotencyKey}
```
- **Type:** String (SET NX)
- **TTL:** 30s
- **Value:** `{serverId}:{requestId}`
- **Set by:** Idempotency filter at start of request processing
- **Released by:** Idempotency filter on completion (DEL)
- **Purpose:** Prevents concurrent duplicate execution of same idempotency key
- **Fallback:** Without this key, concurrent duplicates will be caught by DB UNIQUE constraint (conflict → 409)

---

### Subscription

#### Subscription State Lock
```
{env}:firstclub:sub:lock:{subscriptionId}
```
- **Type:** String (SET NX)
- **TTL:** 5s
- **Value:** `{requestId}`
- **Set by:** Subscription write path before acquiring DB row lock
- **Released by:** Subscription write path on TX commit or rollback
- **Purpose:** Reduce optimistic lock conflicts by serializing concurrent mutations at Redis layer
- **Fallback:** Proceed without Redis lock; rely on `@Version` optimistic locking

#### Active Subscription Count (per merchant)
```
{env}:firstclub:sub:active:count:{merchantId}
```
- **Type:** String (counter)
- **TTL:** 300s (5 min)
- **Value:** Integer count
- **Set by:** Projection rebuild on subscription status changes
- **Read by:** Merchant dashboard reads
- **Fallback:** `SELECT COUNT(*) FROM subscriptions_v2 WHERE merchant_id=? AND status='ACTIVE'`
- **Invalidation:** On any SUBSCRIPTION_ACTIVATED or SUBSCRIPTION_CANCELLED event

---

### Payment

#### Payment Intent Dedup (short window)
```
{env}:firstclub:payment:intent:dedup:{merchantId}:{idempotencyKey}
```
- **Type:** String (SET NX)
- **TTL:** 60s
- **Value:** `{paymentIntentId}`
- **Set by:** Payment intent creation on first success
- **Read by:** Payment intent creation on retry
- **Fallback:** DB UNIQUE on `idempotency_key` column
- **Purpose:** Fast short-circuit for rapid retries in the same minute

#### Payment Capture Lock
```
{env}:firstclub:payment:capture:lock:{paymentIntentId}
```
- **Type:** String (SET NX)
- **TTL:** 10s
- **Value:** `{requestId}`
- **Set by:** Capture path before SELECT FOR UPDATE
- **Released by:** Capture path on completion
- **Purpose:** Prevent concurrent capture attempts from the gateway callback and explicit confirm API
- **Fallback:** DB `business_fingerprint` UNIQUE constraint catches the second attempt

---

### Refund

#### Refund Lock (per payment intent)
```
{env}:firstclub:refund:lock:{paymentIntentId}
```
- **Type:** String (SET NX)
- **TTL:** 10s
- **Value:** `{requestId}`
- **Set by:** Refund creation path
- **Released by:** Refund path on completion
- **Purpose:** Serialize concurrent refund requests against same payment (ceiling check + insert must be atomic)
- **Fallback:** DB SELECT FOR UPDATE on payment_intents_v2 (already in place; Redis reduces contention)

---

### Webhook

#### Webhook Event Delivery Lock
```
{env}:firstclub:webhook:delivery:lock:{eventId}:{endpointId}
```
- **Type:** String (SET NX)
- **TTL:** 30s
- **Value:** `{deliveryAttemptId}`
- **Set by:** Webhook delivery dispatcher before making HTTP call
- **Released by:** Dispatcher on response (success or failure)
- **Purpose:** Prevent two JVM instances from delivering same event to same endpoint simultaneously
- **Fallback:** `FOR UPDATE SKIP LOCKED` on `webhook_delivery_attempts`

#### Endpoint Disabled Marker
```
{env}:firstclub:webhook:endpoint:disabled:{endpointId}
```
- **Type:** String (flag)
- **TTL:** None (persists until explicitly deleted)
- **Value:** `1`
- **Set by:** Endpoint auto-disable logic (after 10 consecutive failures)
- **Read by:** Webhook dispatcher before queueing delivery
- **Invalidation:** On operator re-enable of endpoint (DEL key)

---

### Outbox

#### Outbox Event Processed Marker
```
{env}:firstclub:outbox:processed:{eventId}
```
- **Type:** String (SET NX)
- **TTL:** 3600s (1h)
- **Value:** `1`
- **Set by:** Outbox consumer on first successful processing
- **Read by:** Outbox consumer before processing any event
- **Purpose:** Fast short-circuit for recently processed events (prevents re-processing on poller overlap)
- **Fallback:** Check `outbox_events.status = PROCESSED` in DB

---

### Reconciliation

#### Last Recon Run Result Cache
```
{env}:firstclub:recon:result:{date}
```
- **Type:** String (JSON)
- **TTL:** 7200s (2h)
- **Value:** Serialized `ReconBatchResult` JSON
- **Set by:** Reconciliation job on completion
- **Read by:** Admin dashboard "last recon result" endpoint
- **Fallback:** Load from `recon_batches` table

#### Recon Job Lock
```
{env}:firstclub:recon:lock:{date}
```
- **Type:** String (SET NX)
- **TTL:** 3600s (1h)
- **Value:** `{serverId}`
- **Set by:** Reconciliation job start
- **Released by:** Reconciliation job on completion (DEL)
- **Purpose:** Prevent two JVM instances from running recon for the same date simultaneously
- **Fallback:** DB `job_locks` table (already active)

---

### Gateway Routing

#### Gateway Health Cache
```
{env}:firstclub:gw:health:{GATEWAY_NAME}
```
- **Type:** String (JSON)
- **TTL:** 60s
- **Value:** Serialized `GatewayHealthResponseDTO` JSON
- **Set by:** `GatewayHealthServiceImpl.updateGatewayHealth()` — written after every health update persisted to DB
- **Read by:** `PaymentRoutingServiceImpl.gatewayStatus()` on every routing decision
- **Fallback:** `SELECT * FROM gateway_health WHERE gateway_name=?` (defaults to `HEALTHY` for unknown gateways)
- **Invalidation:** TTL expiry; refreshed on each health status update

#### Routing Rule Cache
```
{env}:firstclub:routing:{scope}:{methodType}:{currency}:{retryNumber}
```
- **Type:** String (JSON)
- **TTL:** 300s (5 min)
- **Value:** Serialized `List<GatewayRouteRuleResponseDTO>` JSON (ordered by priority ASC)
- **Scope values:** merchant ID string (e.g., `"42"`) for merchant-specific rules; `"global"` for platform defaults
- **Set by:** `PaymentRoutingServiceImpl.loadRules()` after DB lookup on cache miss
- **Read by:** `PaymentRoutingServiceImpl.loadRules()` on every payment routing decision (hot path)
- **Fallback:** `findActiveRulesForMerchantAndMethodAndCurrency()` or `findPlatformDefaultRules()` DB queries
- **Invalidation:** On `createRouteRule`, `updateRouteRule`, or `deactivateRouteRule` — evicts the exact key for the mutated rule's discriminators
- **Note:** An empty list (`[]`) is also cached to prevent repeated DB calls for merchants with no rules

---

### Rate Limiting

```
{env}:firstclub:rl:apikey:{merchantId}:{keyPrefix}:second
{env}:firstclub:rl:apikey:{merchantId}:{keyPrefix}:minute
{env}:firstclub:rl:apikey:{merchantId}:{keyPrefix}:day
```
- **Type:** Counter (INCR + EXPIRE)
- **TTL:** 1s / 60s / 86400s respectively
- **Value:** Integer request count
- **Set by:** Rate limit filter on every request
- **Read by:** Rate limit filter before processing request
- **Fallback:** Application-layer sliding window counter (no Redis → one counter per JVM instance, no cross-instance coordination)

---

### Ledger

#### Account Balance Cache
```
{env}:firstclub:ledger:balance:{accountId}:{date}
```
- **Type:** String
- **TTL:** 3600s (1h)
- **Value:** Balance as decimal string
- **Set by:** Ledger snapshot job on snapshot materialization
- **Read by:** Ledger balance API, reporting APIs
- **Fallback:** `SELECT SUM(...) FROM ledger_lines WHERE account_id=? AND entry.posted_at<=?`
- **Invalidation:** TTL expiry; forced on `POST /ops/ledger/snapshots/rebuild`

---

### Risk

#### IP Block List
```
{env}:firstclub:risk:ipblock:{ipAddress}
```
- **Type:** String (flag)
- **TTL:** None (persists until removed)
- **Value:** `{addedBy}:{addedAt}`
- **Set by:** `POST /api/v1/risk/ip-blocks`
- **Read by:** Risk filter on every payment intent creation
- **Invalidation:** `DELETE /api/v1/risk/ip-blocks/{ip}` (DEL key)
- **Fallback:** `SELECT 1 FROM ip_block_list WHERE ip_address=?`

#### Velocity Counter
```
{env}:firstclub:risk:velocity:{merchantId}:{customerId}:1m
{env}:firstclub:risk:velocity:{merchantId}:{customerId}:1h
{env}:firstclub:risk:velocity:{merchantId}:{customerId}:24h
```
- **Type:** Counter (INCR + EXPIRE)
- **TTL:** 60s / 3600s / 86400s
- **Value:** Integer payment attempt count
- **Set by:** Risk velocity checker on every payment attempt
- **Read by:** Risk velocity checker before every payment intent creation
- **Fallback:** `SELECT COUNT(*) FROM payment_intents_v2 WHERE customer_id=? AND created_at>?`

---

## Redis Deployment Configuration

When Redis is deployed, configure via:

```properties
spring.data.redis.host=${REDIS_HOST}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.password=${REDIS_PASSWORD}
spring.data.redis.ssl.enabled=true
spring.data.redis.timeout=2000ms
spring.data.redis.connect-timeout=500ms
```

**Failure model:** Redis failures must not break financial operations. All Redis reads have a DB fallback. Redis writes are best-effort — a failure to write a Redis key after a DB commit is not an error.

**Never** make a Redis write inside a DB transaction. Redis and PostgreSQL cannot participate in a distributed 2PC.

---

## Phase 19: Redis Observability and Hit/Miss Analysis

### Measuring Cache Effectiveness

```bash
# Connect to Redis and check keyspace stats
redis-cli INFO keyspace
# db0:keys=12430,expires=11980,avg_ttl=3274000

# Hit/miss ratio
redis-cli INFO stats | grep -E "keyspace_hits|keyspace_misses"
# keyspace_hits:491823
# keyspace_misses:14220
# hit_rate = 491823 / (491823 + 14220) = 97.2%
```

**Target hit rates by key type:**

| Key type | Target hit rate | TTL | Action if below target |
|---|---|---|---|
| Idempotency response cache | > 90% (24h window) | 24 h | Verify key construction matches filter |
| Subscription read cache | > 80% | 5 min | Check invalidation on update paths |
| Webhook dedup fingerprint | > 99% | 24 h | Investigate fingerprint generation |
| Outbox dedup | > 95% during burst | 1 h | Check eviction policy; use `allkeys-lru` |
| Rate limit counters | N/A (incr-only) | 1–60 min | Verify TTL not reset on each request |

### Slowlog Analysis

```bash
# Capture slow Redis commands (> 1 ms)
redis-cli SLOWLOG RESET
redis-cli SLOWLOG GET 10

# Slow commands to watch for:
# KEYS *        — never use in production; use SCAN instead
# Large LRANGE  — paginate, do not fetch full lists
# HGETALL       — use HGET for specific fields on large hashes
```

### Memory Pressure Monitoring

```bash
redis-cli INFO memory | grep -E "used_memory_human|maxmemory_human|mem_fragmentation_ratio"
# used_memory_human:      128.45M
# maxmemory_human:        512.00M
# mem_fragmentation_ratio: 1.12   (healthy range: 1.0–1.5)
```

**Eviction policy:** Set `maxmemory-policy allkeys-lru` to prevent OOM on burst fills.
Redis evicts least-recently-used keys when memory is full, degrading to DB fallback
gracefully rather than throwing errors.

### Cold-Start Warm-Up

After a Redis pod restart all caches are cold. The first 10–30 seconds of traffic will
have 100% miss rate on idempotency keys. This is safe (falls back to PostgreSQL) but adds
15–20 ms per request during warm-up.

To reduce impact, implement a `POST /internal/cache-warm` endpoint that pre-populates
active subscription and merchant keys from PostgreSQL on startup.
This is optional below 200 RPS; required at 500+ RPS where cold-start latency spikes are noticeable.

### Key Inventory Summary (10K active merchants)

| Domain | Approx key count | TTL | Eviction impact |
|---|---|---|---|
| Idempotency response cache | ~100K / day | 24 h | Safe — auto-expires |
| Idempotency processing locks | ~100K / day | 30 s | Very low — fast clean |
| Subscription read cache | ~10K | 5 min | Moderate — needs lru |
| Webhook dedup fingerprints | ~50K / day | 24 h | Moderate |
| Outbox dedup keys | ~50K / day | 1 h | Low |
| Rate limit counters | ~5K active | 1–60 min | Negligible |
| Job lock keys | < 20 | 5 min | Negligible |
