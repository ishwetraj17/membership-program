# High-Throughput Evolution

Honest assessment of current limitations and the concrete steps needed to serve very high transaction-per-second (TPS) volumes.

---

## 1. Honest Current Limitations

The platform is an architecturally clean monolith. It handles thousands of requests per minute well, but has these hard limits:

| Concern | Current limit | Root cause |
|---|---|---|
| **Write throughput** | ~500 TPS sustained | Single PostgreSQL writer; write amplification from outbox + ledger per payment |
| **Connection pool** | 10 HikariCP connections (default) | Every in-flight request consumes a connection; pool exhaustion under load |
| **Scheduler coordination** | No cross-pod locking (yet) | Duplicate renewal/dunning runs if scaled horizontally |
| **Idempotency key storage** | DB table `idempotency_keys` | DB RTT per request; no TTL-based expiry |
| **Token blacklist** | In-memory map | Lost on pod restart; not shared across pods |
| **Rate limiting** | In-memory `LoginRateLimiterService` | Not shared across pods; brute-force possible on multi-pod deploy |
| **Event fan-out** | Polling loop (`OutboxPoller`) | Minimum dispatch latency bounded by poll interval; CPU-burning under low throughput |
| **Read scaling** | Single writer DB serves reads too | Long analytical queries (projections, recon) block write transactions via shared connection pool |
| **Webhook dispatch** | Synchronous HTTP per delivery | One slow endpoint blocks the scheduler thread; no back-pressure |

---

## 2. What Must Change for Very High TPS

"Very high" = 10,000+ payment intents per second across a multi-tenant merchant fleet.

### 2.1 Database layer

| Change | Priority | Notes |
|---|---|---|
| Increase `hikari.maximum-pool-size` to 50–100 | Immediate | Tune to `(core_count × 2) + disk_spindles`; test under load |
| Add PgBouncer in transaction-mode pooling | Medium | Reduces actual PG connections to ~20 while serving 100s of app threads |
| Move to `asyncio`/reactive stack (`r2dbc`) | Long-term | Eliminates thread-per-request bottleneck; higher complexity |
| Shard by `merchant_id` | Long-term | Each shard runs ~5,000 TPS; 10 shards = 50,000 TPS |
| Partition `outbox_events`, `ledger_entries` by month | 6–12 months | Keeps index sizes bounded; `VACUUM` efficient |

### 2.2 Outbox and event dispatch

| Change | Priority | Notes |
|---|---|---|
| Reduce `OutboxPoller` interval to 200ms | Immediate | Currently every few seconds; lower latency for event delivery |
| Increase poller concurrency (`LIMIT` in query) | Immediate | Fetch 100 events/tick instead of 10 |
| Replace polling with Kafka / Redpanda publish | Medium | Publisher inserts row + publishes to broker topic; broker delivers to consumers |
| Separate event types into dedicated topics | Long-term | Allows per-type consumer group scaling |

### 2.3 In-memory state → Redis

| Current | New | When to migrate |
|---|---|---|
| `TokenBlacklistService` (in-memory `ConcurrentHashMap`) | Redis SET with TTL matching JWT expiry | > 2 pods |
| `LoginRateLimiterService` (per-pod counter) | Redis sliding window counter | > 2 pods |
| `IdempotencyKeyEntity` (DB table) | Redis hash with TTL (24h) | > 1000 TPS idempotency checks |
| `FeatureFlagService` (DB query per check) | Redis-backed cache with write-through | > 10,000 flag checks/min |
| Caffeine in-process cache | Redis cluster | > 2 pods (cache invalidation across pods) |

---

## 3. Sync vs Async Path Evolution

### Current: predominantly synchronous

```
Client → HTTP → Controller → Service → DB (2–5 tables) → HTTP Response
                                    ↘ OutboxEvent (written in same TX)
```

The entire payment flow up to `PaymentIntent` creation and ledger posting is synchronous. This is correct for the current scale — it gives strong consistency and simple error handling.

### Near-term: async for side effects

Move non-blocking work off the hot path:

```
Client → HTTP → Controller → Service → DB (core tables) → HTTP Response
                                    ↘ OutboxEvent → [OutboxPoller] → Ledger posting
                                                              ↘ Webhook dispatch
                                                              ↘ Projection update
```

**Steps:**
1. Move ledger postings for successful payments into an outbox event handler (`PaymentSucceededHandler` already exists).
2. Move webhook dispatch to the outbox-driven `MerchantWebhookDeliveryScheduler`.
3. Move projection updates to `ProjectionEventListener` (already exists).

### Long-term: async for everything except money movement

```
Client → HTTP → Idempotency check (Redis) → DB (payment_intent only) → Respond 202 Accepted
                                          ↘ Kafka topic → Payment processor worker
                                                       → Ledger service (separate)
                                                       → Webhook service (separate)
```

The core principle: **the client gets an acknowledgement; eventual consistency handles the rest**. The payment intent record is the source of truth; all derived state (ledger, webhooks, projections) is built asynchronously.

---

## 4. Projections, Broker, and Partitioned Persistence Strategy

### Current projection architecture

```
Domain event (in-memory Spring event)
  → ProjectionEventListener
  → ProjectionUpdateService
  → CustomerBillingSummaryProjection / MerchantDailyKpiProjection (DB tables)
```

**Weakness:** Projections are updated synchronously in the same JVM that processed the business event. If the projection update fails, the caller's transaction rolls back. Projections and domain logic are tightly coupled.

### Target projection architecture

```
Domain event → OutboxEvent → Kafka topic
                                  ↘ Projection Consumer (separate process)
                                     → read model DB (separate schema or Postgres read replica)
```

**Benefits:**
- Projection failures don't roll back business transactions.
- Projection consumers can be scaled independently.
- Projections can be rebuilt by replaying the topic from offset 0 — the `ReplayService` already models this intent.
- Read model DB can be a different technology (e.g., Elasticsearch for full-text, ClickHouse for analytics).

### Steps to reach this state

1. **Immediate:** Ensure every domain event is written to `outbox_events` before it is published in-process (already true for most events).
2. **Phase A:** Add a Kafka `OutboxPublisher` alongside `OutboxPoller`. Both can run initially; phase out the poller.
3. **Phase B:** Create a `ProjectionConsumer` that subscribes to `firstclub.domain.*` topics and updates projections. Disable `ProjectionEventListener`.
4. **Phase C:** Move projection tables to a read replica with `synchronous_commit=off` for lower write latency.

### Partitioned persistence targets

When tables exceed 50M rows:

```sql
-- Partition ledger_entries by month
CREATE TABLE ledger_entries (
    id         BIGSERIAL,
    created_at TIMESTAMP NOT NULL,
    ...
) PARTITION BY RANGE (created_at);

-- Monthly child tables (automate creation via script)
CREATE TABLE ledger_entries_2026_03
  PARTITION OF ledger_entries
  FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
```

Archive strategy: detach partitions older than 24 months and move to cold storage (S3 via `pg_dump` or `COPY TO`). The partition key must be included in all WHERE clauses for partition pruning to work — enforce this via query reviews before partitioning.

---

## 5. Summary: Evolution Milestones

| Milestone | Description | When |
|---|---|---|
| M1 — Lock schedulers | Wire `JobLockService` into all scheduler classes | Now (Phase 20 complete) |
| M2 — Redis for blacklist + rate limit | Replace in-memory state with Redis | After first 2-pod deploy |
| M3 — Increase pool + PgBouncer | HikariCP max-pool + proxy pool | Before scaling beyond 2 pods |
| M4 — Kafka outbox publisher | Add Kafka alongside existing poller | At 1000+ TPS sustained |
| M5 — Extract async workers | Separate outbox worker, webhook dispatcher, scheduler runner | At 5000+ TPS |
| M6 — Shard by merchant_id | ~10 PostgreSQL shards | At 10,000+ TPS or 10M+ merchants |
| M7 — Separate Ledger service | Own DB, API, strong consistency | When financial compliance requires isolation |
| M8 — Projection consumer | Kafka-driven read model builder | When projection lag becomes an SLA issue |
