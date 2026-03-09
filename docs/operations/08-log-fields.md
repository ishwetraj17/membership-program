# Log Fields Reference

**Status:** Canonical  
**Last updated:** Phase 3 — HTTP Request Context & Structured Logging  
**Applies to:** All environments

---

## 1. Overview

Every log line emitted by the platform carries a set of structured fields drawn from the SLF4J MDC (Mapped Diagnostic Context).  These fields are named in the constants class `StructuredLogFields` and are set/cleared by the filter pipeline.

When JSON logging is enabled (Logstash encoder in production), each MDC key appears as a top-level field in the JSON log entry, enabling structured queries in ELK/OpenSearch without log parsing.

---

## 2. Request-Tracing Fields

These fields are set by the HTTP filter pipeline on every incoming request and cleared in the filter's `finally` block.

| MDC Key | `StructuredLogFields` constant | Set by | Description |
|---|---|---|---|
| `requestId` | `REQUEST_ID` | `RequestIdFilter` | Unique ID for this HTTP request. Taken from `X-Request-Id` header, or generated as UUIDv4 if absent. Echoed on response. |
| `correlationId` | `CORRELATION_ID` | `CorrelationIdFilter` | Logical flow correlation ID. Taken from `X-Correlation-Id` header, or defaults to `requestId`. Echoed on response. |
| `apiVersion` | `API_VERSION` | `ApiVersionFilter` | Client-declared API version string (e.g., `2025-01-01`). Omitted from MDC if header is absent. |

### Why are `requestId` and `correlationId` different?

| | `requestId` | `correlationId` |
|---|---|---|
| **Scope** | One HTTP request | One logical business flow |
| **Uniqueness** | Always unique per request | Same value across related requests |
| **Example use** | Find all log lines for a single API call | Find all log lines for a checkout session, a dunning cycle, or a webhook delivery sequence |
| **Populated by** | Server (generated if absent) | Client (optional; defaults to requestId) |

In a single-request scenario, both values are identical.  In a multi-request flow (e.g., a merchant retrying a payment), the client sends the same `X-Correlation-Id` on every attempt so the entire flow is searchable as a unit.

---

## 3. Business-Entity Fields

These fields are set by service or security code during request processing and removed after the relevant operation completes.  They are NOT set by the filter pipeline.

| MDC Key | `StructuredLogFields` constant | Populated by | Description |
|---|---|---|---|
| `merchantId` | `MERCHANT_ID` | Security filter chain (post-auth) | Authenticated merchant tenant ID. |
| `actorId` | `ACTOR_ID` | Security filter chain (post-auth) | Authenticated user ID or service account name. |
| `customerId` | `CUSTOMER_ID` | Service code | Customer entity ID for the operation in progress. |
| `subscriptionId` | `SUBSCRIPTION_ID` | Service code | Subscription entity ID. |
| `paymentIntentId` | `PAYMENT_INTENT_ID` | Service code | Payment intent entity ID. |
| `invoiceId` | `INVOICE_ID` | Service code | Invoice entity ID. |
| `eventId` | `EVENT_ID` | Event processing code | Domain event entity ID. |

### Usage pattern for business-entity fields

```java
MdcUtil.set(StructuredLogFields.SUBSCRIPTION_ID, subscription.getId());
try {
    processRenewal(subscription);
} finally {
    MdcUtil.remove(StructuredLogFields.SUBSCRIPTION_ID);
}
```

Never call `MDC.put(...)` directly — use `MdcUtil.set(...)` which is null-safe: null values remove the key rather than storing a blank entry.

---

## 4. HTTP Access Log Fields

These fields are set by `RequestLoggingFilter` when it emits the per-request access log line.

| MDC Key | `StructuredLogFields` constant | Description |
|---|---|---|
| `method` | `HTTP_METHOD` | HTTP method (GET, POST, etc.) |
| `path` | `HTTP_PATH` | Request URI path |
| `status` | `HTTP_STATUS` | HTTP response status code |
| `durationMs` | `DURATION_MS` | End-to-end request duration in milliseconds |

### Example access log line (JSON)

```json
{
  "@timestamp": "2026-03-09T12:00:00.123Z",
  "level": "INFO",
  "logger": "c.f.p.web.RequestLoggingFilter",
  "message": "request completed [method=POST path=/api/v2/subscriptions status=201 durationMs=47 requestId=b2c8d3e4-... correlationId=b2c8d3e4-...]",
  "requestId": "b2c8d3e4-f5a6-7890-abcd-ef1234567890",
  "correlationId": "b2c8d3e4-f5a6-7890-abcd-ef1234567890",
  "apiVersion": "2025-01-01",
  "merchantId": "42"
}
```

---

## 5. Filter Ordering and MDC Lifecycle

The filter pipeline populates MDC in a strict order:

```
RequestIdFilter          (HIGHEST_PRECEDENCE + 0)
 ├─ sets:  requestId
 ├─ echoes: X-Request-Id
 └─ clears: requestId (in finally)

CorrelationIdFilter      (HIGHEST_PRECEDENCE + 1)
 ├─ reads:  requestId from MDC (set above)
 ├─ sets:  correlationId
 ├─ echoes: X-Correlation-Id
 └─ clears: correlationId (in finally)

ApiVersionFilter         (HIGHEST_PRECEDENCE + 2)
 ├─ sets:  apiVersion (only if header present)
 └─ clears: apiVersion (in finally)

RequestContextFilter     (HIGHEST_PRECEDENCE + 10)
 ├─ reads:  requestId, correlationId, apiVersion from MDC
 ├─ binds:  RequestContext to RequestContextHolder (thread-local)
 └─ clears: RequestContextHolder (in finally)

[Security filter chain]
 └─ updates RequestContextHolder with merchantId, actorId

RequestLoggingFilter     (LOWEST_PRECEDENCE - 10)
 ├─ reads:  requestId, correlationId from MDC
 └─ logs:   access log line after chain completes
```

---

## 6. Why Thread-Local Cleanup Matters

The platform runs on a servlet container thread pool. Without cleanup, a thread that processed `merchantId=42` may be reused for `merchantId=99`'s next request, carrying over the old MDC value.

Rules:
1. Every value placed in MDC MUST be removed in a `finally` block.
2. Every value placed in `RequestContextHolder` MUST be cleared in a `finally` block.
3. Async operations that cross thread boundaries (e.g., `@Async`, `CompletableFuture`) MUST capture and re-bind the `RequestContext` in the target thread.

---

## 7. Sensitive Data Policy

**Never** log or set the following values in MDC:
- API keys or bearer tokens
- Passwords or credential material
- Card numbers, CVVs, or banking details
- Personal data fields (email, phone, full name) unless in a specifically approved audit context

`RequestLoggingFilter` is deliberately restricted to method, path, status, and duration — no headers, no query parameters, no request bodies.

---

## 8. Querying in ELK / OpenSearch

Common search patterns for structured log queries:

| Goal | Query |
|---|---|
| All log lines for a single request | `requestId: "b2c8d3e4-..."` |
| Full flow trace (multi-request) | `correlationId: "flow-session-99"` |
| All errors for a merchant | `merchantId: "42" AND level: ERROR` |
| Slow requests (> 500 ms) | `durationMs: [500 TO *]` |
| Payment intent trace | `paymentIntentId: "pi-12345" AND level: (INFO OR WARN OR ERROR)` |
| Subscription lifecycle | `subscriptionId: "sub-001" AND level: INFO` |
