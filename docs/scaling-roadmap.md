# Scaling Roadmap

Technical roadmap for evolving the FirstClub Membership Platform from a well-structured monolith to a horizontally scalable, service-distributed architecture.

---

## 1. Current Monolith Boundaries

The platform is packaged as a single Spring Boot application with clearly separated domain packages. Each package is effectively a bounded context:

| Package | Domain | Likely split candidate |
|---|---|---|
| `com.firstclub.payments` | Payment intents, methods, gateway routing, refunds, disputes | Yes — high TPS, audit requirements |
| `com.firstclub.ledger` | Double-entry ledger, revenue recognition | Yes — strong consistency, own DB |
| `com.firstclub.notifications.webhooks` | Outbound delivery, retry | Yes — async-first, fan-out pattern |
| `com.firstclub.outbox` | Outbox poll + event dispatch | Yes — broker integration point |
| `com.firstclub.recon` | Reconciliation, statement import, settlements | Yes — batch-heavy, separate SLA |
| `com.firstclub.risk` | Rule evaluation, manual review queue | Yes — ML-ready |
| `com.firstclub.merchant` | Merchant accounts, API keys, modes | Yes — multi-tenant boundary |
| `com.firstclub.membership` | Users, plans, subscriptions (legacy v1) | Low priority — stable |
| `com.firstclub.subscription` | Subscriptions v2, schedules | Medium — dunning dependency |
| `com.firstclub.dunning` | Dunning policies, retry scheduler | Medium — coupled to payments |
| `com.firstclub.catalog` | Products, prices, price versions | Low — mostly read traffic |
| `com.firstclub.customer` | Customer profiles, notes | Low — reference data |
| `com.firstclub.billing` | Invoices, discounts, tax | Medium — billing computation |
| `com.firstclub.reporting.projections` | Read-model projections, snapshots | Yes — read-model split |
| `com.firstclub.platform` | Idempotency, crypto, ops, state machine | Shared library |

### Monolith strengths (keep these now)

- Single DB schema with referential integrity across domains
- Transactional consistency across ledger + payment + subscription in one commit
- Zero network hops for inter-domain service calls
- Simple deployment and operational tooling
- Fast development iteration

---

## 2. Future Service Split Candidates

### Phase A — Extract async workers (low risk, high benefit)

Split out as independent processes sharing the same DB initially:

1. **Outbox Worker** — runs `OutboxPoller` only; read/write to `outbox_events` via JDBC; other domains publish to outbox via API or DB insert.
2. **Webhook Dispatcher** — runs `MerchantWebhookDeliveryScheduler`; consumes delivery queue; HTTP-only outbound. Can be scaled horizontally.
3. **Scheduler Worker** — runs all `@Scheduled` jobs (renewals, dunning, recon, revenue recognition, snapshots); one active replica at a time via `JobLockService` or ShedLock.

### Phase B — Extract read model (medium risk, high read-scalability)

4. **Projection Service** — `reporting.projections` package becomes a separate service. Reads from the outbox broker topic or change-data-capture stream to build read models. Has its own read replica DB or `PostgreSQL` schema.

### Phase C — Extract payments (higher risk, requires careful migration)

5. **Payment Service** — `payments.*`, `dunning.*` extracted with their own DB schema. Communicates with other services via async events (outbox/broker) or synchronous internal API.

### Phase D — Extract ledger (highest risk, consistency-critical)

6. **Ledger Service** — full double-entry ledger with its own DB. All financial postings go through a ledger API with idempotency keys. Revenue recognition runs as a ledger scheduler.

---

## 3. Redis / Kafka / Read-Model Evolution Path

### Current state: in-process, DB-backed

| Concern | Current implementation | Future target |
|---|---|---|
| Idempotency keys | `idempotency_keys` PostgreSQL table | Redis with TTL — lower latency, auto-expiry |
| Outbox fan-out | Polling `outbox_events` table | Kafka topic per domain event type |
| Rate limiting | In-memory `LoginRateLimiterService` | Redis sliding window counter |
| Token blacklist | `TokenBlacklistService` in memory | Redis SET with TTL matching JWT expiry |
| Session/cache | Caffeine in-process | Redis — survives pod restarts, shared across pods |
| Job locking | `job_locks` DB table | ShedLock on `shedlock` table or Redis-backed |
| Feature flags | `feature_flags` DB table | Redis-cached with write-through, TTL refresh |

### Kafka topic design (when broker is introduced)

```
firstclub.domain.payment.succeeded
firstclub.domain.subscription.activated
firstclub.domain.subscription.cancelled
firstclub.domain.invoice.created
firstclub.domain.refund.issued
firstclub.domain.dispute.opened
```

- Partition key: `merchant_id` — all events for a merchant land on the same partition in order.
- Consumer groups: `outbox-worker`, `projection-builder`, `webhook-dispatcher`, `audit-streamer`.
- `OutboxPoller` becomes an `OutboxKafkaPublisher`; the polling loop is replaced by a `KafkaTemplate.send()`.

---

## 4. `merchant_id` Sharding Strategy

`merchant_id` is the natural shard key for this platform. All high-volume tables already carry a `merchant_id` column.

### Target shard topology

```
merchants 1–999       → shard-1 (PostgreSQL instance)
merchants 1000–1999   → shard-2
...
```

### Tables to shard by `merchant_id`

| Table | Current row volume driver |
|---|---|
| `payments` | High — one row per charge |
| `payment_intents_v2` | High |
| `payment_attempts` | High |
| `outbox_events` | Very high |
| `ledger_entries` / `ledger_lines` | Very high |
| `subscriptions_v2` | Medium |
| `invoices` | High |
| `merchant_webhook_deliveries` | High |
| `recon_mismatches` | Medium |
| `revenue_recognition_schedules` | Medium |

### Cross-shard concerns

- **Idempotency keys** — use Redis or a dedicated idempotency shard keyed by `(merchant_id, idempotency_key)`.
- **Global reporting** — read from all shards via a read replica or projection DB that ingests from all shards.
- **Ledger invariant checks** — run per-merchant, then aggregate results.

### Near-term preparation (no sharding yet — do this now)

- [ ] Confirm every high-volume table query passes `merchant_id` in `WHERE` clause (already true for most).
- [ ] Add composite indexes `(merchant_id, status)` and `(merchant_id, created_at)` to payment, invoice, outbox tables.
- [ ] Avoid cross-merchant queries in hot paths — use per-merchant projections instead.

---

## 5. Partitioning Targets for Large Tables

Before or instead of sharding, PostgreSQL declarative partitioning by date + merchant is viable for the largest tables.

### Range partitioning by `created_at` (monthly)

```sql
-- Example: partition outbox_events by month
CREATE TABLE outbox_events_2026_01
  PARTITION OF outbox_events
  FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
```

**Recommended first targets:**

| Table | Partition key | Estimated partition size |
|---|---|---|
| `outbox_events` | `created_at` (monthly) | Millions of rows/month at scale |
| `ledger_entries` | `created_at` (monthly) | Append-only, never updated |
| `ledger_lines` | `created_at` (monthly) | ~3x ledger_entries volume |
| `payment_attempts` | `created_at` (monthly) | Retry-heavy, short query windows |
| `domain_events` | `created_at` (monthly) | Event log, immutable |

### Benefits

- `VACUUM` and `ANALYZE` run per-partition — less blocking on a hot table.
- Old partitions can be detached and archived (or dropped) without a full-table scan.
- Index sizes shrink proportionally — queries with date range filters are dramatically faster.

### When to partition

Partition when a table exceeds ~50M rows or when `VACUUM` autovacuum visibly affects query latency. For most tables in a new deployment, this is a 12–18 month horizon.
