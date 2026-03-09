# Idempotency Model

The platform enforces a three-layer idempotency model to guarantee that a client retrying a request does not cause double-charges, duplicate records, or inconsistent state.

---

## Why Three Layers

Each layer defends against a different failure mode:

| Layer | Defense | Status | Failure mode covered |
|---|---|---|---|
| 1. Idempotency key table (PostgreSQL) | Persistent deduplification across restarts | **Active** | Client retry after server restart, long-duration retry |
| 2. Redis response cache | In-memory lookup for repeated requests under the key TTL | **Active** | High-frequency retry within a short window |
| 3. In-flight Redis NX lock | Prevents concurrent duplicate execution | **Active** | Two requests with identical key arriving simultaneously |

All three layers are active. Layers 2 and 3 degrade gracefully to Layer 1 when Redis is unavailable.

---

## Layer 1: Idempotency Key Table

### Table: `idempotency_keys`

```
idempotency_keys
├── key             VARCHAR(255) PK  -- composite: "{merchantId}:{rawKey}"
├── merchant_id     VARCHAR(255)     -- tenant identifier
├── endpoint_signature VARCHAR(255)  -- e.g. "POST:/api/v2/subscriptions"
├── request_hash    VARCHAR          -- SHA-256 of method+path+body
├── status_code     INTEGER          -- HTTP status of original response
├── response_body   TEXT             -- Serialised original response
├── content_type    VARCHAR(128)     -- Content-Type of original response
├── created_at      TIMESTAMP
└── expires_at      TIMESTAMP        -- TTL for cleanup
```

### On Request Arrival

```
1. Extract Idempotency-Key header from request
2. UNIQUE SELECT on (merchant_id, key)
   - Not found → proceed; insert key row at start; continue
   - Found + request_hash matches → return stored (status_code, response_body) immediately
   - Found + request_hash does NOT match → return 422 IDEMPOTENCY_KEY_REUSED
3. Execute business logic
4. UPDATE idempotency key row with status_code + response_body
```

### Stored Response Replay

When a client retries with the same `Idempotency-Key` and same body, the server:
- Returns the exact original `status_code`
- Returns the exact original `response_body` (already serialized)
- Does NOT re-execute any business logic
- Does NOT write any new DB records
- The client cannot distinguish a fresh response from a replayed one

---

## Layer 2: Redis Response Cache

**Enabled by:** `app.redis.enabled=true`  
**Key:** `{env}:firstclub:idem:resp:{merchantId}:{rawKey}`  
**TTL:** `ttlHours × 3600` seconds (set per `@Idempotent(ttlHours=…)`)  
**Value type:** `IdempotencyResponseEnvelope` (JSON) — contains `requestHash`, `endpointSignature`, `statusCode`, `responseBody`, `contentType`

**Behaviour:**
- Cache HIT + hash+endpoint match → replay response immediately, zero DB I/O
- Cache HIT + hash mismatch OR endpoint mismatch → `409 IDEMPOTENCY_CONFLICT`
- Cache MISS → fall through to Layer 1 (DB lookup)
- After DB HIT on a processed record → seed Redis so subsequent retries skip DB
- 5xx responses are **not** cached — transient failures should be retried

**Graceful degradation:** when Redis is unavailable, `RedisIdempotencyStore.isEnabled()` returns `false` and all Layer-2 operations are skipped transparently.

---

## Layer 3: In-Flight NX Lock

**Enabled by:** `app.redis.enabled=true`  
**Key:** `{env}:firstclub:idem:lock:{merchantId}:{rawKey}`  
**TTL:** 30 seconds (auto-released; safety valve against crashes)  
**Value type:** `IdempotencyProcessingMarker` (JSON) — contains `requestHash`, `endpointSignature`, `lockedAt`, `requestId`

**Behaviour:**
1. First request: `SET NX EX 30` on the lock key → succeeds → proceeds to execute
2. Concurrent duplicate: `SET NX` fails → `409 IDEMPOTENCY_IN_PROGRESS` returned immediately
3. First request completes → writes result to DB + Redis cache → releases lock via `DEL`
4. Client retries → hits Redis cache (Layer 2) → replayed in microseconds

**Graceful degradation:** when Redis is unavailable the NX lock is skipped; the DB's primary key unique constraint on the composite `key` column still prevents duplicate execution.

---

## Request Header

Clients supply the idempotency key via:

```
Idempotency-Key: {your-unique-key}
```

Recommended key format: `{clientRef}:{timestamp-ms}` or a V4 UUID.

The `Idempotency-Key` header is required on all mutating endpoints where duplicate execution has financial consequences:
- `POST /api/v2/payments/intents` — payment creation
- `POST /api/v2/payments/{id}/capture` — payment capture
- `POST /api/v2/subscriptions` — subscription creation
- `POST /api/v2/refunds` — refund request

---

## Error Responses

| Scenario | HTTP Status | Error Code |
|---|---|---|
| Key not present on required endpoint | 400 | `IDEMPOTENCY_KEY_REQUIRED` |
| Key exceeds 80 characters | 400 | `IDEMPOTENCY_KEY_TOO_LONG` |
| Same key, different request body or endpoint | 409 | `IDEMPOTENCY_CONFLICT` |
| Concurrent duplicate request (lock held) | 409 | `IDEMPOTENCY_IN_PROGRESS` |

---

## Expiry and Cleanup

- DB rows are retained for **audit purposes** beyond the TTL
- Cleanup job runs nightly: `DELETE FROM idempotency_keys WHERE expires_at < NOW()`
- Default TTL: 24 hours (overridable per endpoint by configuration)
- Even after Redis TTL expiry, DB row answers any DB-layer query

---

## What Is Not Covered by Idempotency Keys

| Operation | Why idempotency key is not the guard |
|---|---|
| Webhook delivery | Uses `business_fingerprint` UNIQUE constraint on `payment_transactions_v2` |
| Revenue recognition | Uses `status PENDING/RECOGNIZED` + REQUIRES_NEW per row |
| Reconciliation | Re-run is naturally idempotent (upsert by batch_date) |
| Ledger entries | Uses `business_fingerprint` UNIQUE constraint on `ledger_entries` |
| Outbox dedup | Uses `idempotency_key` column UNIQUE on `outbox_events` |

---

## Phase 4 — Full Idempotency Foundation

### Status Lifecycle

Phase 4 introduces an explicit status lifecycle for every idempotency record.

```
First request arrives
        │
        ▼
  ┌──────────────┐
  │  PROCESSING  │  ← placeholder written to DB at request start
  └──────┬───────┘
         │
    ┌────┴─────────────────────┐
    │                          │
  2xx/3xx                    5xx
    │                          │
    ▼                          ▼
┌─────────────┐     ┌───────────────────┐
│  COMPLETED  │     │ FAILED_RETRYABLE  │  ← client may retry same key
└─────────────┘     └───────────────────┘
```

| Status             | Meaning                                                                  |
|--------------------|--------------------------------------------------------------------------|
| `PROCESSING`       | Placeholder inserted; original request is executing.                    |
| `COMPLETED`        | Operation finished; response is stored and will be replayed on retry.   |
| `FAILED_RETRYABLE` | Operation failed with a transient (5xx) error; retry with same key OK.  |
| `FAILED_FINAL`     | Operation failed with a permanent error; new key required.              |
| `EXPIRED`          | TTL elapsed before completion (for future GC extension).                |

Null `status` means the record predates Phase 4 (legacy). Null-safe behaviour is applied throughout: a record with null status and a stored response body is treated as `COMPLETED`; one without a response body is treated as `PROCESSING`.

### Conflict Semantics (422 vs 409)

| Situation                                       | Status | Error code               |
|-------------------------------------------------|--------|--------------------------|
| Same key, same endpoint, same body, in-flight   | **409** | `IDEMPOTENCY_IN_PROGRESS` |
| Same key, **different endpoint**                | **422** | `IDEMPOTENCY_CONFLICT`    |
| Same key, same endpoint, **different body**     | **422** | `IDEMPOTENCY_CONFLICT`    |

422 signals a client programming error that requires a new key.  
409 signals a transient race condition — the client should wait and retry.

### Replay Headers

```
X-Idempotency-Replayed: true
X-Idempotency-Original-At: 2025-01-15T10:30:00.123456   (when available)
```

`X-Idempotency-Original-At` is present only for DB replays of Phase-4 records (where `completed_at` is stored). It is absent for Redis replays and legacy records.

### Stuck-PROCESSING Cleanup

`IdempotencyCleanupScheduler` resets records stuck in `PROCESSING` back to `FAILED_RETRYABLE` after a configurable threshold (default 5 min), allowing clients to retry.

```properties
app.idempotency.stuck-processing-threshold-minutes=5
app.idempotency.stuck-cleanup-interval-ms=60000
```

### Checkpoint Pattern

For multi-step operations, intermediate state can be recorded using `IdempotencyCheckpointService.record(...)`. Checkpoints are stored in `idempotency_checkpoints` and allow a retrying handler to detect partial completion without re-executing completed steps.

### Redis Fallback Modes

| Mode           | Behaviour when Redis is unavailable                         |
|----------------|-------------------------------------------------------------|
| `DEGRADE_TO_DB`| (default) Redis calls silently fall back to DB.            |
| `REJECT`       | Returns 503 Service Unavailable to the client.             |

```properties
app.idempotency.redis-failure-mode=DEGRADE_TO_DB
```
