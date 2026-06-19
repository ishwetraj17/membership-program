# Observability & Multi-Node Readiness

This document describes the production observability surface (Prometheus metrics) and the
distributed-state primitives (rate limiting, login lockout) that make the membership platform safe
to run on multiple pods behind a load balancer.

> Scope: production **hardening** only — no architecture, business-logic, membership-feature, or
> auth redesign. Single-node behaviour is unchanged; the distributed paths activate under the
> `redis` profile.

---

## 1. Prometheus metrics endpoint

Metrics are published in the OpenMetrics text format that a Prometheus server scrapes.

| Property            | Value                                                            |
|---------------------|------------------------------------------------------------------|
| Endpoint            | `GET /actuator/prometheus`                                       |
| Format              | OpenMetrics / Prometheus text exposition                        |
| Registry            | `micrometer-registry-prometheus` (auto-configured)              |
| Common tag          | `application="FirstClub Membership Program"` on every series    |
| Auth (default/prod) | **ADMIN only** — `/actuator/health` is the only public endpoint |

### Exposure

`management.endpoints.web.exposure.include` lists `prometheus` (plus `health,info,metrics`). In the
`prod` profile only `health,prometheus` are surfaced; everything else stays hidden.

### Security

Spring Security restricts actuator access (`SecurityConfig`):

```
/actuator/health, /actuator/health/**   → public      (load-balancer / k8s probes)
/actuator/prometheus, /actuator/metrics/** → hasRole(ADMIN)
/actuator/**                            → hasRole(ADMIN)
```

A Prometheus scraper authenticates with a bearer token for a service account that has the `ADMIN`
role. Alternatively (common in k8s) bind actuator to a separate management port reachable only from
the cluster network — no code change, just `management.server.port`.

### Quick check

```bash
# Public health — no auth
curl -s localhost:8080/actuator/health

# Scrape — requires an ADMIN bearer token
TOKEN=$(curl -s localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | jq -r .token)

curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/actuator/prometheus | grep '^membership_'
```

---

## 2. Metric catalog

All meters carry the common `application` tag. Names below are the Micrometer names; Prometheus
renders them with `_` separators and a `_total` suffix on counters.

### Cache
| Meter | Type | Tags | Meaning |
|-------|------|------|---------|
| `membership.entitlements.cache` | counter | `result=hit\|miss\|error` | Entitlements (checkout) cache outcomes |
| `membership.entitlements.lookup` | counter | `outcome=ok\|fallback` | Entitlements read path |
| `membership.entitlements.invalidation` | counter | `scope=user\|all` | Cache invalidations |
| `cache.gets` / `cache.puts` / `cache.evictions` | counter | `cache`, `result=hit\|miss` | Caffeine plan/tier cache stats (`recordStats` enabled) |

### Entitlements / Outbox
| Meter | Type | Tags | Meaning |
|-------|------|------|---------|
| `membership.outbox.pending` | gauge | — | Unrelayed outbox rows (backlog) |
| `membership.outbox.dispatched` | counter | — | Successfully relayed events |

### Trials & Savings
| Meter | Type | Tags | Meaning |
|-------|------|------|---------|
| `membership.trial.started` | counter | `days` | Trials started |
| `membership.trial.converted` | counter | — | Trials converted to paid |
| `membership.trial.expired` | counter | `reason=no_auto_renew\|charge_failed` | Trials expired |
| `membership.savings.recorded` | counter | `type` | Member savings recorded (amount) |
| `membership.intro.applied` | counter | `type` | Introductory offers applied |

### Auth
| Meter | Type | Tags | Meaning |
|-------|------|------|---------|
| `membership.auth.login` | counter | `result=success\|failure\|locked` | Login outcomes |
| `membership.ratelimit.rejected` | counter | — | Requests rejected with HTTP 429 |

### Scheduler
| Meter | Type | Tags | Meaning |
|-------|------|------|---------|
| `membership.scheduler.run` | timer | `job` | Per-job run count + duration |
| `membership.scheduler.failures` | counter | `job` | Per-job failures |

### Dashboard / business KPIs
| Meter | Type | Tags | Meaning |
|-------|------|------|---------|
| `membership.subscription.events` | counter | `type=CREATED\|CANCELLED\|RENEWED\|UPGRADED\|DOWNGRADED\|EXPIRED\|REFUNDED\|TRIAL_STARTED` | Subscription lifecycle — covers created / cancelled / renewals / upgrades / downgrades |
| `membership.trial.converted` | counter | — | Trial conversions |
| `membership.payment` | timer | `operation=charge\|refund`, `result=success\|failure\|pending` | Payment success/failure + latency |
| `resilience4j.retry.calls` | counter | `kind` | Payment retries (see [PAYMENTS.md](PAYMENTS.md)) |
| `resilience4j.circuitbreaker.state` | gauge | `state` | Payment circuit-breaker state |
| `membership.coupon.redeemed` | counter | `code` | Coupon redemptions |

> The subscription lifecycle counters are emitted from a single after-commit
> `SubscriptionEventListener`, so created/cancelled/renewals/upgrades/downgrades are all available
> as `type` slices of one series rather than duplicated counters.

### Example PromQL
```promql
# Payment failure rate (5m)
sum(rate(membership_payment_total{result="failure"}[5m]))
  / sum(rate(membership_payment_total[5m]))

# Cancellations per hour
sum(rate(membership_subscription_events_total{type="CANCELLED"}[1h]))

# Login lockouts
sum(rate(membership_auth_login_total{result="locked"}[5m]))

# Outbox backlog
membership_outbox_pending
```

---

## 3. Distributed rate limiting

**Problem:** rate limiting was node-local — N pods meant N× the intended limit.

**Design:** a `RateLimiter` strategy with two interchangeable implementations selected by profile:

| Implementation | Profile | Backing store |
|----------------|---------|---------------|
| `LocalRateLimiter` (default) | `!redis` | In-process Bucket4j token buckets in a bounded Caffeine map |
| `RedisRateLimiter` | `redis` | Redis token bucket via an atomic Lua script |

`RateLimitFilter` only resolves the client key (`user:<name>` when authenticated, else `ip:<addr>`)
and translates a rejection into HTTP 429 — the counting is delegated to the strategy.

The Redis script implements the **same greedy token-bucket math** as Bucket4j (`capacity` tokens
replenished smoothly over `rate-limit.refill-period-seconds`), evaluated atomically server-side so
the read-refill-consume sequence can't race across pods. Keys use a `{clientKey}` hash-tag so both
bucket keys land in the same Redis Cluster slot, and they self-expire after two refill windows.

**No behavioural regression:** identical limit, identical config keys, identical filter API. Only
*where the counter lives* changes.

---

## 4. Distributed login lockout

**Problem:** lockout state was node-local — an attacker could spread guesses across pods and never
trip a single node's counter.

**Design:** a `LoginAttemptService` strategy, same profile-based selection:

| Implementation | Profile | Backing store |
|----------------|---------|---------------|
| `LocalLoginAttemptService` (default) | `!redis` | Caffeine, `expireAfterWrite(window)` |
| `RedisLoginAttemptService` | `redis` | Redis `INCR` + `EXPIRE(window)` per username |

`AuthServiceImpl` calls `isLockedOut` / `recordFailure` / `recordSuccess`; the threshold and window
come from the existing `security.lockout.max-attempts` / `window-minutes`. The Redis variant resets
the TTL on each failure, mirroring the in-process `expireAfterWrite` semantics, and deletes the key
on success — so lockout behaviour is identical, just shared across instances.

---

## 5. Enabling the distributed profile

```bash
SPRING_PROFILES_ACTIVE=prod,redis \
REDIS_HOST=redis.internal REDIS_PORT=6379 \
java -jar membership-program.jar
```

The `redis` profile simultaneously switches **cache**, **rate limiting**, and **lockout** to their
shared Redis-backed implementations, so all three pieces of cross-node state stay consistent.
Without the profile every node runs fully self-contained (the single-node default).

---

## 6. Validation

* `LocalRateLimiterTest` — capacity exhaustion and per-key isolation of the token bucket.
* `LocalLoginAttemptServiceTest` — threshold lockout, reset on success, per-username tracking.
* `ObservabilityEndpointTest` — `/actuator/health` public; `/actuator/prometheus` returns 401
  unauthenticated, 403 for a non-admin, and 200 with app + JVM meters and the `application` tag for
  an admin.
* `mvn clean test` — full suite green (the strategy interfaces keep the default in-process
  behaviour, so existing tests are unaffected).
