# Read Paths

This document describes how data is read from the system, when to use source-of-truth tables vs projections, and how caching affects reads.

---

## Categories of Reads

| Category | Who Uses It | Latency Expectation | Source |
|---|---|---|---|
| Transactional reads | Service layer during write operations | Low; same request | Source-of-truth tables |
| Support reads | Customer support tooling | Medium | Source-of-truth tables |
| Admin reads | Internal operators | Medium | Source-of-truth tables + aggregate queries |
| Dashboard reads | Merchant-facing analytics UI | Low; high volume | Projections + snapshots |
| Report reads | Finance / compliance | Can be high-latency | Source-of-truth tables or materialized views |
| Timeline reads | Audit / debugging | Medium | `domain_events` table |
| Audit reads | Compliance / investigation | Any | `ledger_entries`, `domain_events` |

---

## Transactional Reads (In-Flight Writes)

These reads happen within a write-path transaction to load state before mutating it.

| Read | Purpose |
|---|---|
| `subscriptions_v2` by ID with `@Lock(PESSIMISTIC_WRITE)` or version check | Prevent concurrent subscription state changes |
| `payment_intents_v2` by ID `SELECT FOR UPDATE` | Verify intent is not terminal before writing attempt |
| `invoice_sequences` by merchant_id `SELECT FOR UPDATE` | Atomic invoice number increment |
| `revenue_recognition_schedules` by ID in `REQUIRES_NEW` | Idempotency guard — check if already `POSTED` |
| `refunds_v2` count for payment | Verify `refunded_amount + new_refund <= captured_amount` |

---

## Support Reads

Support team queries a specific customer's subscription state, invoices, and payment history.

```
GET /api/v2/subscriptions/{subscriptionId}
GET /api/v1/invoices/user/{userId}
GET /api/v2/payments/{paymentIntentId}
GET /api/v2/refunds/payment/{paymentId}
GET /api/v2/disputes/{disputeId}
```

These go directly to source-of-truth tables with no caching. Strong consistency is required for support work — a support rep must see the exact current state.

---

## Admin Reads

Admin tooling combines multiple entity reads for reconciliation review and ledger inspection.

```
GET /api/v1/admin/recon/daily?date=YYYY-MM-DD     → ReconBatch + ReconMismatch rows
GET /api/v1/admin/recon/daily.csv?date=YYYY-MM-DD  → CSV export
GET /api/v1/ledger/entries?from=...&to=...          → Paginated LedgerEntry list
GET /api/v1/ledger/balances                         → Account balances
GET /ops/health/deep                                → Aggregate subsystem health
GET /ops/dlq                                        → DLQ event list
```

These queries run directly on source-of-truth tables. Results are paginated. Admin reads are infrequent enough not to require a cache.

---

## Dashboard Reads (Projection-Backed)

Dashboards should read from pre-materialized projections, not from live aggregate queries on source tables.

### Available Projections

| Projection | Source | Update Trigger | Table / Cache |
|---|---|---|---|
| Subscription status | `subscriptions_v2` events | On subscription state change event | `subscription_projections` |
| Invoice summary | `invoices` + `invoice_lines` | On invoice state change event | Planned: `proj:invoice-summary` Redis key |
| Payment summary | `payment_intents_v2` + attempts | On payment state change | Planned: `proj:payment-summary` Redis key |
| Ledger balance snapshot | `ledger_lines` | Nightly `DailySnapshotScheduler` | `ledger_balance_snapshots` |

### When to Use Projections vs Source Tables

| Use Case | Recommendation |
|---|---|
| Real-time support read (single entity) | Source table — must be exact |
| Dashboard aggregate (many entities) | Projection / snapshot |
| Financial audit / compliance | Source table — projections may lag |
| Report for a closed period | Snapshot (faster; period is immutable) |
| Live count of PENDING invoices | Source table or real-time query |
| Revenue chart for the last 30 days | Ledger snapshot (nightly materialized) |

---

## Ledger Balance Snapshot Reads

`LedgerBalanceSnapshot` rows are materialized nightly by `DailySnapshotScheduler`.

```sql
-- Query pattern for a closed historical period:
SELECT * FROM ledger_balance_snapshots
WHERE snapshot_date BETWEEN :fromDate AND :toDate
  AND merchant_id = :merchantId
  AND account_code = 'REVENUE_SUBSCRIPTIONS'
ORDER BY snapshot_date;
```

**Important:** For yesterday and earlier, use snapshots. For today (open period), query `ledger_lines` directly.

---

## Timeline Reads

The `domain_events` table is an append-only log. Timeline reads scan it backward by `aggregate_id`.

```
GET /api/v1/timeline/{entityType}/{entityId}
```

Returns the sequence of events that affected an entity. Used for:
- Debugging unexpected state
- Support escalation
- Audit investigation

**Cache (Future):** `{env}:firstclub:timeline:{merchantId}:{entityType}:{entityId}` with short TTL (60s) for repeated support reads on the same entity.

---

## Cache Usage (Current vs Future)

### Current (No Redis Deployed)

All reads go to PostgreSQL. No read cache exists today.

### Planned Redis Read Cache

| Key Pattern | TTL | When to Invalidate |
|---|---|---|
| `proj:invoice-summary:{merchantId}:{invoiceId}` | 300s | On invoice state change |
| `proj:payment-summary:{merchantId}:{intentId}` | 300s | On payment state change |
| `proj:sub-status:{merchantId}:{subscriptionId}` | 300s | On subscription state change |
| `merchant:settings:{merchantId}` | 3600s | On merchant settings update |
| `ff:global:{flagKey}` | 600s | On flag update |
| `timeline:{merchantId}:{entityType}:{entityId}` | 60s | On new event for entity |

**Rules for all cache reads:**
1. Check Redis first (fast path)
2. On cache miss → query PostgreSQL 
3. Populate cache with result
4. On Redis error → fall through to PostgreSQL silently (never fail a read because Redis is down)

---

## Pagination

All list endpoints support cursor-based or offset-based pagination.

```
GET /api/v2/subscriptions?merchantId=X&page=0&size=50
GET /api/v1/ledger/entries?from=2025-01-01&to=2025-01-31&page=0&size=100
GET /api/v1/invoices/user/{userId}?page=0&size=20
```

**Rule:** Never return unbounded lists. Default page size 20, max 200. This applies to all list endpoints without exception.

---

## Read Path Summary

```
Client Request
    │
    ▼
[Redis fast-path] ─────── HIT ──────► Return cached projection
    │ MISS
    ▼
[PostgreSQL]
    ├─ Source-of-truth tables (exact current state)
    │   invoices, subscriptions_v2, payment_intents_v2,
    │   ledger_entries/lines, refunds_v2, disputes ...
    │
    └─ Projection tables (pre-computed aggregates)
        ledger_balance_snapshots, subscription_projections
    │
    ▼
[Populate Redis cache]
    │
    ▼
Return to Client
```

---

## Phase 11 — Ops & Summary Projection Tables

### New Projection Tables

| Table | PK | Source of truth | Updated by |
|---|---|---|---|
| `subscription_status_projection` | `(merchant_id, subscription_id)` | `subscriptions_v2`, `invoices`, `dunning_attempts`, `payment_intents_v2` | `SUBSCRIPTION_*`, `INVOICE_CREATED`, `PAYMENT_SUCCEEDED` |
| `invoice_summary_projection` | `(merchant_id, invoice_id)` | `invoices` | `INVOICE_CREATED`, `PAYMENT_SUCCEEDED` |
| `payment_summary_projection` | `(merchant_id, payment_intent_id)` | `payment_intents_v2`, `payments`, `payment_attempts` | `PAYMENT_INTENT_CREATED`, `PAYMENT_ATTEMPT_*`, `PAYMENT_SUCCEEDED`, `REFUND_*`, `DISPUTE_OPENED` |
| `recon_dashboard_projection` | `BIGSERIAL id` + unique `(COALESCE(merchant_id,-1), business_date)` | `recon_reports`, `recon_mismatches` | `RECON_COMPLETED` |

### Read APIs

```
GET /api/v2/admin/projections/subscriptions
    ?merchantId=&status=&customerId=&page=&size=

GET /api/v2/admin/projections/invoices
    ?merchantId=&status=&overdueOnly=&customerId=&page=&size=

GET /api/v2/admin/projections/payments
    ?merchantId=&status=&customerId=&page=&size=

GET /api/v2/admin/projections/recon-dashboard
    ?merchantId=&from=&to=&page=&size=
```

All endpoints are secured with `ADMIN` role and are sourced exclusively from the
projection tables — never from hot transactional tables.

### Redis Hot-Cache (OpsProjectionCacheService)

| Projection | Cache key | TTL |
|---|---|---|
| Subscription status | `{env}:firstclub:proj:sub-status:{merchantId}:{subscriptionId}` | 120 s |
| Invoice summary | `{env}:firstclub:proj:invoice-summary:{merchantId}:{invoiceId}` | 120 s |
| Payment summary | `{env}:firstclub:proj:payment-summary:{merchantId}:{paymentIntentId}` | 120 s |

Recon dashboard rows are not cached individually (they are low-throughput aggregates).
Cache is gracefully disabled when `app.redis.enabled=false`.

### Rebuild

```
POST /api/v2/admin/projections/rebuild/subscription_status
POST /api/v2/admin/projections/rebuild/invoice_summary
POST /api/v2/admin/projections/rebuild/payment_summary
POST /api/v2/admin/projections/rebuild/recon_dashboard
```

Rebuild scans the source tables directly (not the event log) — so it is fast and
correct regardless of how many events have been emitted.

### Full-Re-Read Strategy

All four projections use a **full-re-read** approach: on every relevant event the
service reads the current state from the source tables and rewrites the projection
row.  This avoids incremental-math drift and makes each projection trivially
rebuildable.

---

## Phase 12: Unified Ops Timeline (`ops_timeline_events`)

### Overview

Phase 12 adds an **append-only event timeline** that gives support and ops a single
chronological record of what happened to any entity.  Unlike the projection tables
(which store *current state*), the timeline stores the *full event history*.

### Table: `ops_timeline_events`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | Auto-increment |
| `merchant_id` | BIGINT | Tenant key |
| `entity_type` | VARCHAR(64) | CUSTOMER, SUBSCRIPTION, INVOICE, PAYMENT_INTENT, REFUND, DISPUTE |
| `entity_id` | BIGINT | ID within the entity type |
| `event_type` | VARCHAR(64) | Original domain event type |
| `event_time` | TIMESTAMP | When the event occurred |
| `title` | VARCHAR(255) | Human-readable one-liner |
| `summary` | TEXT | Optional detail (gateway, failure reason, etc.) |
| `related_entity_type` | VARCHAR(64) | Cross-entity link (nullable) |
| `related_entity_id` | BIGINT | Cross-entity link (nullable) |
| `correlation_id` | VARCHAR(128) | Propagated from domain event |
| `causation_id` | VARCHAR(128) | Propagated from domain event |
| `payload_preview_json` | TEXT | First 500 chars of event payload |
| `source_event_id` | BIGINT | Origin `domain_events.id` — used for dedup |
| `created_at` | TIMESTAMP | Row insertion time |

### Indexes

| Index | Columns | Purpose |
|---|---|---|
| `idx_timeline_dedup` | `(source_event_id, entity_type, entity_id) WHERE source_event_id IS NOT NULL` | Replay dedup |
| `idx_timeline_entity` | `(entity_type, entity_id, event_time DESC)` | Primary query path |
| `idx_timeline_correlation` | `(merchant_id, correlation_id)` | Correlation trace |
| `idx_timeline_event_type` | `(merchant_id, event_type, event_time DESC)` | Admin event-type filter |

### Multi-row strategy

One domain event may produce **up to two timeline rows** so that each affected
entity has its own history:

| Domain event | Primary row | Secondary row |
|---|---|---|
| SUBSCRIPTION_ACTIVATED | SUBSCRIPTION/{id} | CUSTOMER/{customerId} |
| INVOICE_CREATED | INVOICE/{id} | SUBSCRIPTION/{subscriptionId} |
| PAYMENT_SUCCEEDED | PAYMENT_INTENT/{id} | INVOICE/{invoiceId} |
| DISPUTE_OPENED | DISPUTE/{id} | CUSTOMER/{customerId} |
| RECON_COMPLETED | — (skipped) | — |

### Redis cache

- Key: `{env}:firstclub:timeline:{merchantId}:{entityType}:{entityId}`
- TTL: 60 seconds (hot — timeline lookups are frequent during incidents)
- Built by: `TimelineCacheService`
- Factory method: `RedisKeyFactory.opsTimelineKey(merchantId, entityType, entityId)`

### API endpoints

| Method | Path | Description |
|---|---|---|
| GET | `/api/v2/admin/timeline` | Generic paginated (entityType + entityId + merchantId) |
| GET | `/api/v2/admin/timeline/customer/{id}` | Customer event history |
| GET | `/api/v2/admin/timeline/subscription/{id}` | Subscription history |
| GET | `/api/v2/admin/timeline/invoice/{id}` | Invoice history |
| GET | `/api/v2/admin/timeline/payment/{id}` | Payment intent history |
| GET | `/api/v2/admin/timeline/by-correlation/{id}` | Cross-entity correlation trace |

All endpoints require `ADMIN` role and `merchantId` query param for tenant isolation.

---

## Phase 13 — Unified Admin Search

### Purpose

Phase 13 adds a **cross-entity unified search layer** that allows support and ops
to locate any entity by a single operational identifier without knowing which table
to look in.  A single search bar (or API call) fans out to all relevant repositories
and aggregates results.

### Search dimensions

| Identifier | Entity located | Repository method |
|---|---|---|
| Invoice number | `Invoice` | `findByInvoiceNumberAndMerchantId` |
| `gateway_txn_id` | `Payment` | `findByGatewayTxnIdAndMerchantId` |
| `refund_reference` | `RefundV2` | `findByRefundReferenceAndMerchantId` |
| Customer email | `Customer` | `findByMerchantIdAndEmailIgnoreCase` |
| Subscription ID (numeric) | `SubscriptionV2` | `findByMerchantIdAndId` |
| Payment intent ID (numeric) | `PaymentIntentV2` | `findByMerchantIdAndId` |
| Event ID (numeric) | `DomainEvent` | `findByIdAndMerchantId` |
| Correlation ID | `DomainEvent` (many) | `findByCorrelationIdAndMerchantIdOrderByCreatedAtAsc` |

### Query detection in `SearchService.search()`

The aggregated `GET /api/v2/admin/search?q=...` endpoint auto-detects the query type:

1. **Email** — if `q` contains `@` → customer email lookup only.
2. **Numeric** — if `q` is a positive integer → subscription ID, payment intent ID, event ID (all three attempted).
3. **String** — always attempted regardless of type → invoice number, gateway ref, correlation ID.

### Flyway migration: V44

`V44__search_indexes.sql` adds the following B-tree indexes:

| Table | Index | Columns |
|---|---|---|
| `invoices` | `idx_invoices_merchant_invoice_number` | `(merchant_id, invoice_number)` |
| `payments` | `idx_payments_merchant_gateway_txn_id` | `(merchant_id, gateway_txn_id)` |
| `refunds_v2` | `idx_refunds_v2_merchant_refund_reference` | `(merchant_id, refund_reference) WHERE NOT NULL` |
| `customers` | `idx_customers_merchant_email_lower` | `(merchant_id, lower(email))` |
| `domain_events` | `idx_domain_events_merchant_correlation_id` | `(merchant_id, correlation_id) WHERE NOT NULL` |
| `domain_events` | `idx_domain_events_merchant_event_type` | `(merchant_id, event_type)` |
| `payment_intents_v2` | `idx_payment_intents_v2_invoice_id` | `(invoice_id) WHERE NOT NULL` |
| `subscriptions_v2` | `idx_subscriptions_v2_merchant_customer` | `(merchant_id, customer_id)` |

### API endpoints

| Method | Path | Description |
|---|---|---|
| GET | `/api/v2/admin/search?q=...&merchantId=...` | Aggregated fan-out across all dimensions |
| GET | `/api/v2/admin/search/by-correlation/{id}?merchantId=...` | All events sharing a correlation ID |
| GET | `/api/v2/admin/search/by-invoice-number/{n}?merchantId=...` | Exact invoice number match |
| GET | `/api/v2/admin/search/by-gateway-ref/{ref}?merchantId=...` | Payment + refund gateway ref match |

All endpoints require `ADMIN` role.  `merchantId` is mandatory on every endpoint
to enforce tenant isolation at the DB query level.

### Redis cache

- Key pattern: `{env}:firstclub:search:{merchantId}:{sha256(queryType:queryValue)}`
- TTL: **30 seconds** (lower than timeline's 60 s — search results are incident-time volatile)
- Built by: `SearchCacheService`
- Factory method: `RedisKeyFactory.searchKey(merchantId, queryHash)`
- Graceful degradation: cache miss falls through to live DB queries

### Result DTO: `SearchResultDTO`

| Field | Description |
|---|---|
| `resultType` | `SearchResultType` enum (INVOICE, PAYMENT_INTENT, PAYMENT, REFUND, CUSTOMER, SUBSCRIPTION, DOMAIN_EVENT) |
| `primaryId` | Entity PK |
| `merchantId` | Tenant ID (caller can verify no cross-merchant leakage) |
| `displayLabel` | Human-readable label (invoice number, email, event type) |
| `status` | Current entity status string |
| `matchedField` | Which field matched (e.g., `"invoiceNumber"`, `"correlationId"`) |
| `matchedValue` | The matched value (echoed back for UI confirmation) |
| `apiPath` | Relative path to the detailed entity endpoint |
| `createdAt` | Entity creation timestamp |

**Security**: `clientSecret`, `phone`, `billingAddress`, `shippingAddress`, and raw
event `payload` are never included in any `SearchResultDTO`.

