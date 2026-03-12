# Financial Audit Trail

## Overview

Every financial mutation in the platform is recorded in the `audit_entries` table as an immutable audit event. The audit trail is:

- **Compliance-grade**: written atomically via a dedicated `REQUIRES_NEW` transaction so that audit entries commit even when the business transaction rolls back.
- **Tamper-evident**: no update or delete paths are exposed through the API or service layer.
- **Queryable**: entries are indexed by entity, merchant, and operation type for efficient compliance queries.

---

## Architecture

```
HTTP Request
    │
    ▼
Service Method  ◄──────── @FinancialOperation annotation
    │
    ▼
FinancialAuditAspect  (@Around)
    ├── pjp.proceed()  ──► Service logic executes
    │       │
    │       ├── SUCCESS → AuditEntryService.record(success=true)
    │       │                  └── REQUIRES_NEW txn → audit_entries
    │       │
    │       └── THROWS  → AuditEntryService.record(success=false, reason=...)
    │                           └── REQUIRES_NEW txn → audit_entries
    │                       └── rethrow original exception
    │
    ▼
Response to caller
```

---

## Schema

The `audit_entries` table was originally created in migration **V50** and extended in **V68** with compliance columns:

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | Auto-generated |
| `request_id` | VARCHAR(64) | HTTP request trace ID |
| `correlation_id` | VARCHAR(64) | Distributed correlation ID |
| `merchant_id` | BIGINT | Tenant scoping |
| `actor_id` | VARCHAR(120) | Authenticated user or service identity |
| `api_version` | VARCHAR(20) | Negotiated API version at time of operation |
| `operation_type` | VARCHAR(80) | Machine-readable constant (e.g. `SUBSCRIPTION_CREATE`) |
| `action` | VARCHAR(80) | Human-readable description |
| `performed_by` | VARCHAR(120) | Actor string from request context |
| `entity_type` | VARCHAR(80) | Domain entity class name |
| `entity_id` | BIGINT | Primary key of mutated entity |
| `success` | BOOLEAN `NOT NULL DEFAULT TRUE` | `FALSE` when the operation threw |
| `failure_reason` | TEXT | Trimmed exception message (max 2 000 chars) |
| `amount_minor` | BIGINT | Optional: monetary amount in minor currency units |
| `currency_code` | VARCHAR(10) | Optional: ISO 4217 currency code |
| `metadata` | TEXT (JSONB) | Optional: arbitrary additional context |
| `ip_address` | VARCHAR(45) | Optional: originating client IP |
| `occurred_at` | TIMESTAMP `NOT NULL` | Write-once creation timestamp |

---

## Annotating a Financial Operation

To instrument a service method, add `@FinancialOperation`:

```java
@FinancialOperation(
    operationType      = "SUBSCRIPTION_CREATE",
    entityType         = "Subscription",
    entityIdExpression = "#result?.id"   // SpEL evaluated on success
)
public Subscription createSubscription(SubscriptionRequest req) {
    // ... business logic
    return subscription;
}
```

### SpEL context for `entityIdExpression`

| Variable | Availability | Description |
|---|---|---|
| `#args[n]` | Always | Positional method argument |
| `#paramName` | Always | Named method argument (requires `-parameters` compile flag, default in Spring Boot) |
| `#result` | Success path only | Return value of the method |

Examples:
- `#result?.id` — extract ID from returned entity
- `#args[0].merchantId` — extract from first argument
- `#subscriptionId` — named parameter

---

## Operation Type Convention

Use `SCREAMING_SNAKE_CASE` constants that describe the mutation:

| Pattern | Examples |
|---|---|
| `{ENTITY}_{VERB}` | `SUBSCRIPTION_CREATE`, `SUBSCRIPTION_CANCEL`, `PLAN_UPDATE` |
| `{DOMAIN}_{VERB}` | `PAYMENT_CONFIRM`, `REFUND_ISSUE`, `INVOICE_VOID` |
| `{ENTITY}_{VERB}_{QUALIFIER}` | `SUBSCRIPTION_UPGRADE_IMMEDIATE`, `PLAN_PRICE_CHANGE` |

---

## Querying the Audit Trail

### REST API (Admin-only)

| Method | Path | Description |
|---|---|---|
| `GET` | `/audit/entries?entityType=Subscription&entityId=42` | All events for one entity |
| `GET` | `/audit/entries/merchant/{merchantId}` | All events for a merchant |
| `GET` | `/audit/entries/failures` | All failed operations |

All endpoints return paginated `AuditEntryDTO` responses and require `ROLE_ADMIN`.

**Example query:**

```http
GET /audit/entries?entityType=Subscription&entityId=42&page=0&size=20
Authorization: Bearer <admin-token>
```

### Direct SQL (compliance / reporting)

```sql
-- All failures in the last 24 hours
SELECT *
FROM   audit_entries
WHERE  success = FALSE
AND    occurred_at > NOW() - INTERVAL '24 hours'
ORDER  BY occurred_at DESC;

-- Full trail for a subscriptionAND    entity_id = 42
SELECT operation_type, actor_id, api_version, success, failure_reason, occurred_at
FROM   audit_entries
WHERE  entity_type = 'Subscription'
AND    entity_id = 42
ORDER  BY occurred_at;

-- Failure rate by operation type (last 7 days)
SELECT   operation_type,
         COUNT(*) FILTER (WHERE success = FALSE) AS failures,
         COUNT(*) AS total,
         ROUND(100.0 * COUNT(*) FILTER (WHERE success = FALSE) / COUNT(*), 2) AS failure_pct
FROM     audit_entries
WHERE    occurred_at > NOW() - INTERVAL '7 days'
GROUP BY operation_type
ORDER BY failure_pct DESC;
```

---

## Failure Alerting

The index `idx_audit_entries_failed` (partial index `WHERE success = FALSE`) makes the following query extremely fast and suitable for polling-based alert jobs:

```sql
SELECT COUNT(*)
FROM   audit_entries
WHERE  success = FALSE
AND    occurred_at > NOW() - INTERVAL '5 minutes';
```

Consider wiring a scheduled job or DataDog monitor against this query to alert when failure counts spike.

---

## Retention

Audit entries must be retained for a minimum of **7 years** to meet financial compliance requirements. Implement a partition-based retention strategy:

- Partition `audit_entries` by `occurred_at` (monthly)
- Drop partitions older than the retention window
- Archive dropped partitions to cold storage (e.g. S3 in Parquet format)

Do **not** `DELETE` individual rows — partition drop is the correct mechanism.

---

## Transaction Isolation

`AuditEntryServiceImpl#record` uses `Propagation.REQUIRES_NEW` to open a new, independent transaction:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public AuditEntry record(...) { ... }
```

This guarantees that:
1. The audit entry is committed even if the caller's transaction rolls back.
2. The audit entry reflects what was *attempted*, not what was *committed*.

This is deliberate: a failed subscription creation still needs an audit trail.
