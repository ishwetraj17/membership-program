# Scaling Path

This document honestly describes the current architectural limits, what the bottlenecks are, and the practical evolution path to higher scale.

---

## Current Architecture — Honest Limits

The platform is a **single-process modular monolith** backed by a **single PostgreSQL instance**.

| Characteristic | Current State |
|---|---|
| Deployment unit | 1 JAR / 1 JVM process |
| Database | 1 PostgreSQL instance (single writer) |
| Background jobs | `@Scheduled` in the same JVM |
| Message broker | None — transactional outbox polled within the same process |
| Caching | Redis 7 infrastructure available via `platform.redis` — disabled by default (`app.redis.enabled=false`). Activate in production to unlock idempotency fast-paths, rate limiting, dedup, and projection caches. |
| Read replicas | Not configured |
| Sharding | Not implemented |

**Realistic throughput today:** Several hundred subscription creates per minute; low thousands of payment callbacks per minute — bounded by PostgreSQL write IOPS and JVM thread pool.

**This is not a criticism.** This is the correct scale for a SaaS product in early-to-mid growth. Adding complexity before the bottleneck is a mistake.

---

## PostgreSQL Bottlenecks

| Bottleneck | When It Appears |
|---|---|
| `ledger_lines` table write volume | Every financial event posts 2+ rows; at 10K payments/day this is ~20K rows/day — fine; at 1M payments/day this needs partitioning |
| `revenue_recognition_schedules` growth | A 1-year subscription generates 365 rows; at 100K subscriptions this is 36M rows/year — partition by `recognition_date` or `merchant_id` |
| `domain_events_outbox` poll loop | Polling `WHERE status=PENDING` at high volume needs a covering index and aggressive cleanup of PROCESSED rows |
| `domain_events` append-only log | Will grow unboundedly; partition by month |
| `idempotency_keys` table | Can grow large; TTL-based cleanup is already scheduled (nightly `IdempotencyCleanupJobImpl`) |
| Long-running aggregate queries | Reconciliation and ledger balance queries on unpartitioned tables will slow as data grows |
| Subscription renewal batch | All renewals for a given day run in series; at 10K renewals/day this may breach the scheduler window |

---

## How Redis Reduces PostgreSQL Pressure

Each Redis key (when implemented) reduces one synchronous DB hit per request:

| Redis Key Use Case | DB Calls Saved |
|---|---|
| Idempotency fast path | 1 `SELECT` on `idempotency_keys` per duplicate request |
| Rate limiting (in Redis) | 1 `SELECT` per request for velocity check |
| Merchant settings cache | 1 `SELECT` per request that needs merchant config |
| Feature flag cache | 1+ `SELECT` per feature flag check |
| Projection caches | 1 aggregate query per dashboard request |
| Gateway health cache | 1 `SELECT` per routing decision |
| Dedup markers | 1 `SELECT` per duplicate webhook/event check |

**Combined:** At 1000 req/s, removing 3 DB reads per request saves 3000 DB reads/s — the difference between a $100/mo DB and a $1000/mo DB.

---

## Projection / Read Separation

**Current:** Read-heavy dashboard queries run on the same DB as writes.

**Evolution step 1 — Add a read replica:**
- Point all `@Transactional(readOnly=true)` queries at a replica
- Reporting, reconciliation summaries, timeline reads go to replica
- Spring's `AbstractRoutingDataSource` can route by transaction read-only flag

**Evolution step 2 — Projection service:**
- Pre-materialized `ledger_balance_snapshots` already exist (built tonight)
- Extend this pattern: subscription cohort metrics, MRR/ARR aggregates, revenue waterfall
- These projections are rebuilt nightly from source-of-truth tables

---

## When to Introduce Kafka / Pulsar

**Current system uses:** Transactional outbox + in-process poller. This is correct and sufficient for:
- < 1M events/day
- Single-tenant or low-tenant deployments
- Single-region

**Introduce Kafka when:**
- Outbox polling can no longer keep up with event production rate (lag grows uncontrollably)
- Need real-time fan-out to multiple independent consumers (e.g., risk, reporting, webhooks each need the same event)
- Need ordered per-key delivery guarantees across multiple downstream systems
- Preparing for service extraction (Kafka becomes the integration contract)

**Migration path:**
1. Keep transactional outbox write intact
2. Add a Kafka producer in the outbox poller (write to both in-process handler AND Kafka topic)
3. Migrate consumers one by one to Kafka topic
4. Remove in-process handler once migration is complete

---

## When to Split Services

The monolith is correct today. Extract a service when **exactly one** of these is true:
- The module needs a different **scaling profile** (e.g., reporting queries kill payment latency)
- The module needs a different **deployment cadence** (e.g., risk engine needs 50ms deployment windows)
- The module has grown to the point where a **separate team** must own it end-to-end
- The module requires a different **technology** (e.g., a streaming analytics engine for fraud)

**Extraction is not justified by "it might be useful later."**

---

## Likely First Service Boundaries

If/when extraction is needed, these modules have the cleanest boundaries:

| Module | Why it's a good extraction candidate |
|---|---|
| **Reconciliation** | Read-heavy batch job; can run on separate compute without real-time latency requirements |
| **Risk Engine** | May need ML integration, separate latency guarantees, external data feeds |
| **Reporting / Projections** | Pure read; separate DB; no writes back to core |
| **Notifications / Webhooks** | High I/O bound (HTTP calls to external endpoints); can scale horizontally |
| **Revenue Recognition** | Pure batch; nightly job; isolation enables independent scheduling |

**Core modules that should NOT be extracted early:**
- Billing, Payments, Ledger, Subscription — these are tightly coupled by ACID transactions. Extracting them requires distributed transaction patterns (Saga, 2PC) which increase failure surface significantly.

---

## Partitioning Candidates

| Table | Partition By | When |
|---|---|---|
| `ledger_lines` | `merchant_id` or `created_at` (monthly) | > 100M rows |
| `revenue_recognition_schedules` | `recognition_date` (monthly) or `merchant_id` | > 50M rows |
| `domain_events` | `created_at` (monthly) | > 50M rows |
| `domain_events_outbox` | `created_at` (cleanup) | > 5M rows PROCESSED |
| `payment_attempts_v2` | `created_at` (monthly) | > 100M rows |
| `recon_mismatches` | `created_at` (monthly) | > 10M rows |

---

## merchant_id as Future Sharding Axis

Every table in the platform already has a `merchant_id` column. This was an intentional design decision.

**Why this matters for sharding:**
- All business operations are already scoped to a single merchant
- No cross-merchant JOIN is ever required for business logic
- `merchant_id` is a natural sharding key: `hash(merchant_id) % N` distributes merchants across shard groups
- Application-level sharding: route all requests for `merchant_id=X` to shard `hash(X) % N`

**Prerequisites before sharding:**
1. Consistent `merchant_id` FK on every table ✓ (already done)
2. No cross-shard queries in business logic ✓ (already true by domain design)
3. PostgreSQL Citus extension or Vitess for horizontal partitioning
4. Service-mesh or middleware to route requests by `merchant_id`

---

## Scale Evolution Roadmap

```
Phase 0 (Now)
  Single PostgreSQL
  In-process outbox poller
  @Scheduled jobs in JVM
  No Redis

  → Supports: ~1K subscriptions/day, ~10K payments/day

Phase 1 (Redis + Read Replica)
  Add Redis (idempotency, rate limit, projection cache)
  Add PostgreSQL read replica
  Route read-only queries to replica

  → Supports: ~10K subscriptions/day, ~100K payments/day

Phase 2 (Partitioning + Kafka)
  Partition high-growth tables
  Add Kafka for event fan-out
  Move background jobs to dedicated worker nodes

  → Supports: ~100K subscriptions/day, ~1M payments/day

Phase 3 (Service Extraction)
  Extract Reconciliation, Risk, Reporting
  Distributed tracing (OpenTelemetry)
  Circuit breakers on inter-service calls

  → Supports: ~1M subscriptions/day, ~10M payments/day

Phase 4 (Sharding)
  Application-level merchant_id sharding
  Citus or Vitess for PostgreSQL horizontal scale

  → Supports: Multi-tenant hyperscale
```

---

## What Is Not Needed (Yet)

| Over-engineering | Why It's Premature |
|---|---|
| Event sourcing (events as primary DB) | Domain events are already appendable; source tables are simpler and correct |
| CQRS with separate write/read models | Projections already provide this pattern; full event-sourced CQRS adds rebuild complexity without proportional benefit |
| Sagas for billing/payment | These are in the same process; distributed sagas are only needed when services are actually split |
| Apache Flink / streaming analytics | Nightly batch reconciliation is sufficient at current scale |
| Multi-region active-active | Single region is fine until geographic distribution is a product requirement |

---

## Phase 19 Evidence: Measured Failure Isolation

The following failure modes were tested in
`src/test/java/com/firstclub/performance/Phase19FailureInjectionTest.java`.
Each scenario is a pure-unit Mockito test (no Postgres or Redis required).

| # | Failure scenario | Guard | Observed behaviour |
|---|---|---|---|
| 1 | Redis unavailable during idempotency check | `RedisAvailabilityService.isAvailable() = false` | `isEnabled()` returns false; all store methods are no-ops; DB path used |
| 2 | DB failure during outbox `processSingleEvent` | `OutboxEventRepository` mock throws `DataAccessException` | Exhausted events written to DLQ; no silent data loss |
| 3 | Stale optimistic lock on concurrent update | `ObjectOptimisticLockingFailureException` injected | Exception propagates; caller receives 409 |
| 4 | Transient DB fault | `TransientDataAccessException` injected | `DataAccessException` surfaces; retry eligible |
| 5 | Scheduler double-fire (two pods) | `JobLockRepository.tryUpdateLock` returns 0 | Second `acquireLock` returns false; job runs once |
| 6 | Webhook consecutive failures at threshold | `CONSECUTIVE_FAILURE_THRESHOLD` reached | `autoDisabledAt` set on endpoint; delivery stops |
| 7 | Idempotency cache hit (positive control) | Redis healthy, key exists | DB not consulted; response replayed from cache |
| 8 | Outbox failure categorization | `categorizeFailure()` classifies exception type | `DB_ERROR`, `HANDLER_ERROR`, `UNKNOWN` mapped correctly |

**Conclusion:** All failure guards activate as designed.  No financial operation
depends on Redis — the PostgreSQL path is the authoritative fallback for every
Redis-dependent feature.

---

## Phase 19: 500K+ Events/Day Requirements

What changes architecturally when crossing 500 000 payment events per day (~6/s average, ~60/s peak):

### Minimum infrastructure changes (still single-node)

```
spring.datasource.hikari.maximum-pool-size=50   # default 10 is too low at 200+ RPS
outbox.poller.batch-size=100                     # up from 50
outbox.poller.threads=2                          # add second poller thread
app.redis.enabled=true                           # mandatory at this scale
```

### PostgreSQL schema changes required

```sql
-- Partition ledger_lines by month (at ~5M rows / 6 months)
-- Partition idempotency_keys by week (at ~13M rows / 26 days)
-- Add partial index for open subscriptions
CREATE INDEX CONCURRENTLY idx_subs_active
    ON subscriptions_v2 (merchant_id, renews_at)
    WHERE status = 'ACTIVE';
```

### What breaks first without the above changes

| Component | Fails at | Failure mode |
|---|---|---|
| Connection pool (default 10) | ~1 000 concurrent requests | `HikariPool-1 - Connection is not available` |
| Outbox (batch=10, 1 thread) | ~20K events/day backlog growth | Queue depth unbounded; events delayed by hours |
| `idempotency_keys` (no cleanup) | ~26 days runtime | Index bloat, `VACUUM` unable to keep up |
| `ledger_lines` unpartitioned | ~6 months (5M rows) | `SUM` aggregates exceed 5 s |
| Revenue recognition nightly | ~50K rows/day | Job runtime > 2 h, overlaps next scheduled run |

### Still not needed at 500K events/day

- Kafka — queue depth stays manageable with tuned Postgres outbox
- Read replicas — a single tuned Postgres handles this read/write ratio
- Service decomposition — module boundaries already exist; extraction adds ops cost without throughput benefit
- Horizontal pod scaling (stateless OK, but DB is the bottleneck, not CPU)

### Transition to Phase 1 scale (>2M events/day, >5K merchants)

At this point the evolution roadmap in §Scale Evolution Roadmap activates:
- Add Postgres read replica for projection queries and recon aggregations
- Introduce Redis as mandatory cache (not optional)
- Enable Spring Batch parallelism for revenue recognition (2–4 partitions)
- Consider Kafka as outbox transport if poller lag > 30 s sustained


---

## Phase 20 — Operationally-Verified Scaling Path

### Six-Stage Evolution Table

| Stage | Label | Key Change | Observable Gate |
|-------|-------|-----------|-----------------|
| 1 | CURRENT | Modular monolith, single PostgreSQL, embedded Quartz scheduler | Baseline: GET /scaling-readiness returns stage_1 = CURRENT |
| 2 | Read Replica | Add PostgreSQL read replica; route reporting and recon queries to replica | Outbox poller lag < 10 s under 5 K subs |
| 3 | Service Extraction | Extract dunning and notification fan-out as independent services | Dunning backlog does not spike at billing peak |
| 4 | Event Streaming | Introduce Kafka/SQS; replace outbox polling with event streaming | Outbox dispatcher removed; consumer lag monitored |
| 5 | Shard by Merchant | Shard PostgreSQL by merchant_id; Quartz cluster mode | OCC rate < 0.1% at 50 K concurrent subs |
| 6 | Full Service Mesh | Per-service data stores; CQRS with event-sourced projections | Zero cross-service synchronous calls on write path |

### Using GET /api/v2/admin/system/scaling-readiness

Returns `architectureShape`, `currentBottlenecks`, `singleNodeRisks`,
`decompositionCandidates`, and the full `evolutionStages` map. Use this
endpoint in capacity reviews to frame the current position on the roadmap.

### Decomposition Priority Order

1. dunning — high-frequency retry loop, natural microservice boundary; extract first
2. notifications/webhooks — stateless fan-out, independently scalable; low coupling
3. reporting — read-only aggregation; safe to route to read replica before extraction
4. risk — event-driven scoring; decouples from write path without state migration
