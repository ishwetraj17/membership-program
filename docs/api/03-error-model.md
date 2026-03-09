# Error Model

All API responses use a consistent error format based on RFC 7807 (Problem Details for HTTP APIs).

---

## Standard Error Response Shape

```json
{
  "type": "https://api.firstclub.com/errors/validation-failed",
  "title": "Validation Failed",
  "status": 400,
  "detail": "The request body contained invalid fields.",
  "instance": "/api/v2/payments/intents",
  "code": "VALIDATION_FAILED",
  "requestId": "req_01HXYZ",
  "timestamp": "2024-01-15T10:30:00Z",
  "errors": [
    {
      "field": "amount",
      "message": "must be greater than 0"
    }
  ]
}
```

| Field | Required | Description |
|---|---|---|
| `type` | Yes | URI reference for the error class |
| `title` | Yes | Human-readable summary |
| `status` | Yes | HTTP status code |
| `detail` | Yes | Specific explanation for this occurrence |
| `instance` | Yes | The request path that generated this error |
| `code` | Yes | Machine-readable error code |
| `requestId` | Yes | Unique request ID for log tracing |
| `timestamp` | Yes | UTC timestamp |
| `errors` | No | Field-level validation errors (for 400 responses) |

---

## HTTP Status to Error Code Mapping

| HTTP Status | Error Codes | When Used |
|---|---|---|
| 400 | `VALIDATION_FAILED`, `IDEMPOTENCY_KEY_REQUIRED`, `INVALID_STATE_TRANSITION` | Request body is invalid, field is missing/wrong type |
| 401 | `UNAUTHORIZED`, `TOKEN_EXPIRED`, `INVALID_API_KEY` | No valid auth token or API key |
| 403 | `FORBIDDEN`, `MERCHANT_NOT_ACTIVE`, `SCOPE_INSUFFICIENT` | Auth valid but not permitted for this resource |
| 404 | `RESOURCE_NOT_FOUND` | Entity not found (subscription, invoice, payment, etc.) |
| 409 | `OPTIMISTIC_LOCK_CONFLICT`, `CONCURRENT_REQUEST`, `SUBSCRIPTION_ALREADY_ACTIVE` | Concurrent modification or duplicate state |
| 422 | `IDEMPOTENCY_KEY_REUSED`, `BUSINESS_RULE_VIOLATION`, `REFUND_CEILING_EXCEEDED`, `PAYMENT_ALREADY_CAPTURED` | Request is valid but violates business logic |
| 429 | `RATE_LIMIT_EXCEEDED` | Too many requests; see `Retry-After` header |
| 500 | `INTERNAL_SERVER_ERROR` | Unexpected failure; not the client's fault |
| 503 | `SERVICE_UNAVAILABLE` | DB or dependency is down; retry after brief delay |

---

## Full Error Code Catalogue

### Auth Errors

| Code | HTTP | Description |
|---|---|---|
| `UNAUTHORIZED` | 401 | No `Authorization` header / no `X-Api-Key` header |
| `TOKEN_EXPIRED` | 401 | JWT has passed its `exp` claim |
| `INVALID_API_KEY` | 401 | API key format invalid or key not found |
| `API_KEY_REVOKED` | 401 | API key exists but has been revoked |
| `FORBIDDEN` | 403 | Authenticated but not authorized for this merchant or resource |
| `MERCHANT_NOT_ACTIVE` | 403 | Merchant account is suspended or not onboarded |
| `SCOPE_INSUFFICIENT` | 403 | API key has a scope that does not cover this endpoint |

---

### Validation Errors

| Code | HTTP | Description |
|---|---|---|
| `VALIDATION_FAILED` | 400 | One or more request fields failed validation |
| `MISSING_REQUIRED_FIELD` | 400 | A required field was absent from the request body |
| `INVALID_FIELD_TYPE` | 400 | A field type is wrong (e.g., string where int expected) |
| `ENUM_VALUE_INVALID` | 400 | Field value is not in the allowed set |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | Mutating endpoint requires `Idempotency-Key` header; it was missing |

---

### Resource Errors

| Code | HTTP | Description |
|---|---|---|
| `RESOURCE_NOT_FOUND` | 404 | Entity with the given ID does not exist for this merchant |
| `MERCHANT_NOT_FOUND` | 404 | Merchant UUID not found |
| `SUBSCRIPTION_NOT_FOUND` | 404 | Subscription UUID not found |
| `INVOICE_NOT_FOUND` | 404 | Invoice UUID not found |
| `PAYMENT_NOT_FOUND` | 404 | Payment intent UUID not found |

---

### Concurrency and State Errors

| Code | HTTP | Description |
|---|---|---|
| `OPTIMISTIC_LOCK_CONFLICT` | 409 | Entity was modified by another process; resend with latest version |
| `CONCURRENT_REQUEST` | 409 | Identical idempotency key already in flight; retry after the first completes |
| `SUBSCRIPTION_ALREADY_ACTIVE` | 409 | Cannot activate an already-active subscription |
| `DUPLICATE_GATEWAY_TRANSACTION` | 409 | Gateway transaction ID already processed |
| `INVALID_STATE_TRANSITION` | 400 | State machine transition is not permitted (e.g., CANCELLED → ACTIVE) |

---

### Business Rule Errors

| Code | HTTP | Description |
|---|---|---|
| `IDEMPOTENCY_KEY_REUSED` | 422 | Same idempotency key was used with a different request body |
| `BUSINESS_RULE_VIOLATION` | 422 | Generic business constraint violation |
| `REFUND_CEILING_EXCEEDED` | 422 | Total refunds would exceed the captured amount |
| `PARTIAL_REFUND_NOT_ALLOWED` | 422 | This plan/merchant does not allow partial refunds |
| `PAYMENT_ALREADY_CAPTURED` | 422 | Cannot capture a payment twice |
| `PAYMENT_ALREADY_REFUNDED` | 422 | Payment is fully refunded; no further refunds possible |
| `INVOICE_ALREADY_VOID` | 422 | Invoice is already void |
| `INVOICE_ALREADY_PAID` | 422 | Invoice is already paid; no further collection |
| `PLAN_NOT_AVAILABLE` | 422 | Plan is inactive or not available for this merchant |
| `RISK_PAYMENT_BLOCKED` | 422 | Payment blocked by risk engine; see `riskDetails` in response |

---

### Rate Limiting

| Code | HTTP | Description |
|---|---|---|
| `RATE_LIMIT_EXCEEDED` | 429 | Request rate exceeded; `Retry-After` header contains seconds to wait |

---

### System Errors

| Code | HTTP | Description |
|---|---|---|
| `INTERNAL_SERVER_ERROR` | 500 | Unexpected server-side error |
| `SERVICE_UNAVAILABLE` | 503 | DB or critical dependency unavailable |

---

## Validation Error Detail

For `VALIDATION_FAILED` (HTTP 400), the `errors` array contains per-field details:

```json
{
  "code": "VALIDATION_FAILED",
  "status": 400,
  "errors": [
    {
      "field": "amount",
      "code": "MUST_BE_POSITIVE",
      "message": "amount must be greater than 0"
    },
    {
      "field": "currency",
      "code": "ENUM_VALUE_INVALID",
      "message": "currency must be one of: INR"
    }
  ]
}
```

---

## Optimistic Lock Conflict Example

A 409 `OPTIMISTIC_LOCK_CONFLICT` includes the current version the client should use on retry:

```json
{
  "code": "OPTIMISTIC_LOCK_CONFLICT",
  "status": 409,
  "detail": "Subscription was modified by another request. Fetch the current version and retry.",
  "currentVersion": 7
}
```

Clients should:
1. Fetch the resource at its current state (`GET /api/v2/subscriptions/{id}`)
2. Apply their change to the returned version
3. Retry the mutation with the new `version` field

---

## Risk-Blocked Payment Example

```json
{
  "code": "RISK_PAYMENT_BLOCKED",
  "status": 422,
  "detail": "This payment was blocked by the risk engine.",
  "riskDetails": {
    "blockReason": "VELOCITY_LIMIT_EXCEEDED",
    "velocityWindow": "1_MINUTE",
    "threshold": 3,
    "observedCount": 5
  }
}
```

---

## Headers in Error Responses

| Header | Present When | Value |
|---|---|---|
| `X-Request-Id` | Always | Unique request trace ID |
| `Retry-After` | 429, 503 | Seconds to wait before retrying |
| `X-RateLimit-Limit` | 429 | Total allowed requests in window |
| `X-RateLimit-Remaining` | Always | Remaining requests in current window |
| `X-RateLimit-Reset` | Always | Unix timestamp when window resets |

---

## Integrity Engine API

The integrity engine uses standard HTTP status codes but also returns a structured run result body.

### `POST /api/v2/admin/integrity/check` — Response model

A successful request (regardless of whether invariants pass or fail) returns `200 OK`:

```json
{
  "id": 42,
  "startedAt": "2024-06-01T10:00:00",
  "finishedAt": "2024-06-01T10:00:03",
  "status": "COMPLETED",
  "totalChecks": 21,
  "failedChecks": 0,
  "summaryJson": "[{\"key\":\"billing.invoice_total_equals_line_sum\",\"status\":\"PASS\",...}]",
  "merchantId": null,
  "invariantKey": null,
  "findings": null
}
```

| Field | Type | Description |
|---|---|---|
| `id` | Long | Run primary key |
| `status` | String | `COMPLETED`, `PARTIAL_FAILURE`, or `ERROR` |
| `totalChecks` | int | Number of checkers executed |
| `failedChecks` | int | Checkers that returned FAIL or ERROR |
| `summaryJson` | String | JSON array with per-checker summary |
| `merchantId` | Long? | Merchant scope, if provided |
| `invariantKey` | String? | Set for single-key runs only |
| `findings` | List? | Populated for `GET /runs/{runId}` detail only |

### `GET /api/v2/admin/integrity/runs/{runId}` — Finding detail

```json
{
  "id": 42,
  "status": "PARTIAL_FAILURE",
  "totalChecks": 21,
  "failedChecks": 1,
  "findings": [
    {
      "id": 155,
      "runId": 42,
      "invariantKey": "ledger.entry_balanced",
      "severity": "CRITICAL",
      "status": "FAIL",
      "violationCount": 3,
      "detailsJson": "{\"violations\":[{\"entityType\":\"LEDGER_ENTRY\",\"entityId\":1001,...}]}",
      "suggestedRepairKey": "ledger.repost_imbalanced_entry",
      "createdAt": "2024-06-01T10:00:02"
    }
  ]
}
```

### Error codes for integrity endpoints

| HTTP | Code | Cause |
|---|---|---|
| 404 | `RESOURCE_NOT_FOUND` | `GET /runs/{runId}` — run does not exist |
| 404 | `RESOURCE_NOT_FOUND` | `POST /check/{invariantKey}` — key not registered |
| 403 | `FORBIDDEN` | Callers without `ROLE_ADMIN` |
