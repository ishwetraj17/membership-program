# Idempotency System

## Overview

The membership platform implements an idempotency layer for all money-moving and
state-changing endpoints. Clients that experience network failures or timeouts
can safely retry a request using the same `Idempotency-Key` header; the server
will return the original response without executing the operation a second time.

---

## How It Works

```
Client ──► POST /api/v2/subscriptions
           Header: Idempotency-Key: <key>
           Body: { ... }
```

### First request (key unknown)
1. Server validates the `Idempotency-Key` header (see rules below).
2. Computes **SHA-256(HTTP method + path + raw request body)** → request hash.
3. Creates a placeholder record in the `idempotency_keys` table (no response stored yet).
4. Executes the handler normally.
5. Stores `(statusCode, responseBody)` in the placeholder record.
6. Returns the response to the client — **HTTP 201** for a new subscription.

### Duplicate request (same key, same body)
1. Server finds the existing record.
2. Verifies the request hash matches (same body → same hash).
3. If the original operation already completed (`statusCode` is set):
   - Replays the stored `statusCode` and `responseBody` verbatim.
   - No new subscription is created.
4. If the original operation is still in flight (placeholder only, concurrent
   requests): falls through and executes normally.

### Conflicting request (same key, different body)
Returns **HTTP 409 Conflict** immediately — no operation is executed.

---

## The `Idempotency-Key` Header

| Property | Rule |
|----------|------|
| Format   | Any string up to **80 characters**. UUIDs are recommended. |
| Required | Yes, on every request to an `@Idempotent` endpoint. Missing → **400**. |
| Uniqueness | Per-client, per-operation. Use a fresh key for each logically distinct request. |
| TTL      | Default **24 hours**. After expiry the key is deleted and may be reused. |

### Example

```http
POST /api/v2/subscriptions HTTP/1.1
Authorization: Bearer <jwt>
Content-Type: application/json
Idempotency-Key: 7f9a1d2e-4c3b-4e5f-8a6b-9c0d1e2f3a4b

{
  "userId": 1,
  "planId": 2,
  "autoRenewal": true
}
```

---

## Response Codes

| Status | Meaning |
|--------|---------|
| `201 Created` | New subscription created (first successful request). |
| `201 Created` | Duplicate request replayed — **same response as the original**. |
| `400 Bad Request` (IDEMPOTENCY_KEY_REQUIRED) | `Idempotency-Key` header is missing or blank. |
| `400 Bad Request` (IDEMPOTENCY_KEY_TOO_LONG) | Key exceeds 80 characters. |
| `409 Conflict` (IDEMPOTENCY_CONFLICT) | Same key reused with a different request body. |

---

## Annotated Endpoints

| Method | Path | Controller |
|--------|------|------------|
| `POST` | `/api/v2/subscriptions` | `SubscriptionV2Controller` |

To mark additional endpoints as idempotent, annotate the handler method:

```java
@PostMapping("/charges")
@Idempotent(ttlHours = 48)          // optional: override default 24-hour TTL
public ResponseEntity<ChargeDTO> createCharge(@Valid @RequestBody ChargeRequestDTO req) {
    // ...
}
```

---

## Request Hash Algorithm

The idempotency fingerprint is computed as follows:

```
hash = SHA-256( HTTP_METHOD_UTF8 + REQUEST_URI_UTF8 + RAW_BODY_BYTES )
```

- `HTTP_METHOD` — e.g. `POST`
- `REQUEST_URI` — decoded path, e.g. `/api/v2/subscriptions`
- `RAW_BODY_BYTES` — exact bytes sent by the client (before JSON parsing)

> **Note:** Whitespace differences in the JSON body produce different hashes.
> Clients are responsible for sending byte-identical bodies when retrying.

---

## Database Schema

```sql
CREATE TABLE idempotency_keys (
    key           VARCHAR(80)  PRIMARY KEY,
    request_hash  VARCHAR(128) NOT NULL,
    response_body TEXT,
    status_code   INT,
    owner         VARCHAR(255),
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMP    NOT NULL
);

CREATE INDEX idx_idempotency_expires_at ON idempotency_keys (expires_at);
```

| Column | Description |
|--------|-------------|
| `key` | Client-supplied idempotency key |
| `request_hash` | SHA-256 fingerprint of the request |
| `response_body` | Stored JSON response (null until operation completes) |
| `status_code` | Stored HTTP status code (null until operation completes) |
| `owner` | Authenticated principal name (null for anonymous) |
| `created_at` | Timestamp when the record was first created |
| `expires_at` | Record is eligible for deletion after this time |

---

## Cleanup Job

Expired records are purged nightly by `IdempotencyCleanupJob`:

- **Schedule:** `0 0 3 * * *` (every day at 03:00 server time)
- **Query:** `DELETE FROM idempotency_keys WHERE expires_at < NOW()`
- After deletion, the key becomes available again for reuse.

To change the cleanup schedule, update the cron expression in
`IdempotencyCleanupJob.java` or externalise it via `application.properties`.

---

## Architecture

```
IdempotencyFilter (OncePerRequestFilter)
    │
    ├─ resolves @Idempotent annotation via RequestMappingHandlerMapping (lazy)
    ├─ delegates to IdempotencyService for DB operations
    └─ captures response body via ContentCachingResponseWrapper

IdempotencyService (@Service)
    ├─ findByKey()         — read-only lookup
    ├─ createPlaceholder() — @Transactional insert of placeholder row
    └─ storeResponse()     — @Transactional update with final response

IdempotencyCleanupJob (@Component)
    └─ @Scheduled deleteExpiredBefore()

IdempotencyKeyEntity (@Entity)
    └─ Hibernate-managed; Flyway migration V5 creates the table in prod
```

---

## Security Notes

- The idempotency key is stored as-is (no PII should be used as keys).
- Use UUIDs rather than guessable sequential IDs to prevent key enumeration.
- The `owner` column is informational only; it does **not** enforce ownership.
  Access control is handled separately by Spring Security (`@PreAuthorize`).
