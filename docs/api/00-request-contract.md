# 00 — Request Contract

> **Scope:** Every inbound HTTP request to the platform API.
> All clients (web, mobile, partner integrations, internal services) must
> follow this contract.

---

## 1. Required headers

| Header | Format | Description |
|---|---|---|
| `Idempotency-Key` | Any opaque string, ≤ 255 chars | **Required on all write operations** (POST, PUT, PATCH).  The key must be unique per logical operation.  Replaying the same key with the same body returns the cached response.  Replaying with a different body returns 409. |

---

## 2. Strongly recommended headers

| Header | Format | Description |
|---|---|---|
| `X-Request-Id` | UUIDv4 string | Unique ID for this HTTP request.  If absent, the server generates one.  **Always present in the response.** Used as the primary key in `audit_entries.request_id`. |
| `X-Correlation-Id` | Any opaque string | Links a logical business flow (e.g., checkout session) across multiple API calls.  Falls back to `X-Request-Id` if absent.  Used in `audit_entries.correlation_id`. |
| `X-API-Version` | `YYYY-MM-DD` date string | Requests a specific API version.  Absent = `ApiVersion.DEFAULT` (`2024-01-01`).  Current stable = `2025-01-01`. |

---

## 3. Response headers (always echoed)

| Header | Source |
|---|---|
| `X-Request-Id` | The `X-Request-Id` from the request, or the generated UUID |
| `X-Correlation-Id` | The `X-Correlation-Id` from the request, or the request ID |

These are echoed so the client can correlate their own log entries with
server-side traces.

---

## 4. MDC (Mapped Diagnostic Context) keys

The request filter pipeline binds the following keys to SLF4J MDC for the
lifetime of each request.  Every log line emitted during request processing
carries these keys automatically.  All key names are constants on
`com.firstclub.platform.logging.StructuredLogFields`; use `MdcUtil` for
null-safe set/remove operations in service code.

### Request-tracing keys (set by dedicated web filters)

| MDC Key | Constant | Source |
|---|---|---|
| `requestId` | `StructuredLogFields.REQUEST_ID` | `X-Request-Id` header, or generated UUIDv4 |
| `correlationId` | `StructuredLogFields.CORRELATION_ID` | `X-Correlation-Id` header, or falls back to `requestId` |
| `apiVersion` | `StructuredLogFields.API_VERSION` | `X-API-Version` header (omitted from MDC if header absent) |

### Business-entity keys (set by application/service code)

Services use `MdcUtil.set(StructuredLogFields.CUSTOMER_ID, id)` (and
analogous calls) to enrich log lines with entity context.  These are **not**
set by the filter pipeline — they are set and cleared by the code that
operates on the relevant entity.

| MDC Key | Constant | Typical setter |
|---|---|---|
| `merchantId` | `StructuredLogFields.MERCHANT_ID` | Set by `RequestContextFilter` from `RequestContext` |
| `actorId` | `StructuredLogFields.ACTOR_ID` | Set by `RequestContextFilter` from `RequestContext` |
| `customerId` | `StructuredLogFields.CUSTOMER_ID` | Set by subscription / customer service code |
| `subscriptionId` | `StructuredLogFields.SUBSCRIPTION_ID` | Set by subscription workflow code |
| `paymentIntentId` | `StructuredLogFields.PAYMENT_INTENT_ID` | Set by payment processing code |
| `invoiceId` | `StructuredLogFields.INVOICE_ID` | Set by invoice / dunning code |
| `eventId` | `StructuredLogFields.EVENT_ID` | Set by event-publishing / webhook code |

### Access-log keys (set by `RequestLoggingFilter`)

| MDC Key | Constant | Description |
|---|---|---|
| `method` | `StructuredLogFields.HTTP_METHOD` | HTTP verb (`GET`, `POST`, …) |
| `path` | `StructuredLogFields.HTTP_PATH` | Request URI path (no query string) |
| `status` | `StructuredLogFields.HTTP_STATUS` | HTTP response status code |
| `durationMs` | `StructuredLogFields.DURATION_MS` | Wall-clock time for the full filter chain |

> **Logstash/ELK users:** These MDC keys appear as top-level JSON fields in
> the structured log output.  Use `requestId` to find all log lines for a
> specific request; use `correlationId` to find all log lines for a full
> checkout or dunning flow.  See `docs/operations/08-log-fields.md` for
> full query patterns and sensitive-data policy.

---

## 5. RequestContext lifecycle

```
HTTP Request arrives
      │
      ▼
 RequestIdFilter  (Order = HIGHEST_PRECEDENCE)
      │  reads X-Request-Id; generates UUIDv4 if absent
      │  sets MDC[requestId]; echoes header in response
      │
      ▼
 CorrelationIdFilter  (Order = HIGHEST_PRECEDENCE + 1)
      │  reads X-Correlation-Id; falls back to MDC[requestId]
      │  sets MDC[correlationId]; echoes header in response
      │
      ▼
 ApiVersionFilter  (Order = HIGHEST_PRECEDENCE + 2)
      │  reads X-API-Version; parses via ApiVersion.parseOrDefault()
      │  sets MDC[apiVersion] only when header is present
      │
      ▼
 RequestContextFilter  (Order = HIGHEST_PRECEDENCE + 10)
      │  reads MDC[requestId], MDC[correlationId], MDC[apiVersion]
      │  builds immutable RequestContext
      │  stores in RequestContextHolder (thread-local)
      │
      ▼
 Security filter chain
      │  (populates merchantId + actorId from JWT/API-key)
      │  calls RequestContextHolder with merchant/actor context
      │
      ▼
 Controller → Service → Repository
      │  access context via RequestContextHolder.require()
      │  or RequestContextHolder.current() for optional access
      │  service code uses MdcUtil.set(CUSTOMER_ID, …) as needed
      │
      ▼
 RequestLoggingFilter  (Order = LOWEST_PRECEDENCE - 10)
      │  wraps entire chain; records start time before chain.doFilter()
      │  after chain: logs method, path, status, durationMs at INFO
      │  skipped for /actuator/** paths
      │
      ▼
 Each filter's finally block clears its own MDC keys:
   RequestIdFilter    → removes MDC[requestId]
   CorrelationIdFilter → removes MDC[correlationId]
   ApiVersionFilter   → removes MDC[apiVersion]
   RequestContextFilter → calls RequestContextHolder.clear()
      ▼
HTTP Response sent
```

> See `docs/operations/08-log-fields.md` for the canonical list of all
> structured log fields, ELK query examples, and the sensitive-data policy.

---

## 6. Accessing the request context in business code

```java
// Safe access — returns Optional<RequestContext>
// Use in service code that may run outside a request (e.g., async worker)
RequestContextHolder.current()
    .map(RequestContext::getRequestId)
    .ifPresent(id -> log.info("Processing request={}", id));

// Mandatory access — throws IllegalStateException if not in a request
// Use in controllers and request-scoped services
RequestContext ctx = RequestContextHolder.require();
Long merchantId = ctx.getMerchantId();
```

---

## 7. Async thread bridging

`RequestContextHolder` uses a plain `ThreadLocal`.  When you dispatch work to
a different thread (e.g., `@Async`, `CompletableFuture.supplyAsync`, virtual
threads), the context is **not propagated automatically**.

```java
// ❌ Wrong — async thread has no RequestContext
CompletableFuture.runAsync(() -> {
    RequestContextHolder.require(); // throws IllegalStateException
});

// ✅ Correct — capture context on the calling thread, pass explicitly
RequestContext ctx = RequestContextHolder.require();
CompletableFuture.runAsync(() -> {
    RequestContextHolder.set(ctx);
    try {
        // ... do work ...
    } finally {
        RequestContextHolder.clear(); // mandatory
    }
});
```

For Spring `@Async` methods, use a `TaskDecorator` that propagates the
context:

```java
@Bean
TaskExecutor platformTaskExecutor() {
    ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
    exec.setTaskDecorator(runnable -> {
        RequestContext ctx = RequestContextHolder.current().orElse(null);
        return () -> {
            try {
                if (ctx != null) RequestContextHolder.set(ctx);
                runnable.run();
            } finally {
                RequestContextHolder.clear();
            }
        };
    });
    return exec;
}
```

---

## 8. API versioning

The `X-API-Version` header follows the `YYYY-MM-DD` scheme.

```
X-API-Version: 2025-01-01
```

In service code, version-gate new behaviour with `ApiVersionContext`:

```java
ApiVersion version = ApiVersionContext.currentOrDefault();
if (version.isAfterOrEqual(ApiVersion.CURRENT)) {
    // new behaviour introduced in 2025-01-01
} else {
    // legacy behaviour
}
```

**Versioning rules:**
- Adding new optional response fields: backward-compatible, no version bump.
- Removing or renaming fields: requires a new version constant in `ApiVersion`.
- Changing field semantics: requires a new version constant.
- Clients that don't send `X-API-Version` receive the `DEFAULT` version
  behaviour (`2024-01-01`), which upholds full backward compatibility.

---

## 9. Error response format

All errors, regardless of exception type, return the same JSON structure:

```json
{
  "message": "Human-readable description of the error",
  "errorCode": "MACHINE_READABLE_CODE",
  "httpStatus": 409,
  "timestamp": "2025-06-15T14:30:00",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "validationErrors": null,
  "errorMetadata": {
    "entityType": "Invoice",
    "entityId": "42",
    "expectedState": "PENDING",
    "actualState": "PAID"
  }
}
```

`errorMetadata` is only present for `BaseDomainException` subclasses and is
`null` for framework exceptions.

---

## 10. Known limits

| Limit | Value | Rationale |
|---|---|---|
| `Idempotency-Key` max length | 255 chars | Fits in a `VARCHAR(255)` index without prefix truncation |
| `X-Request-Id` max value stored | 64 chars | UUIDs are 36 chars; provider trace IDs may be up to ~64 chars |
| `X-API-Version` valid range | `2024-01-01` to `CURRENT` | Older dates default to base behaviour; future dates are rejected |
| MDC propagation | Per-thread only | Async threads require explicit delegation (see §7) |
