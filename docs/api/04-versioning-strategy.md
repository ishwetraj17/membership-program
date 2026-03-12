# API Versioning Strategy

## Overview

The FirstClub Membership Platform uses **date-based API versioning** (`YYYY-MM-DD`) rather than numeric major/minor versions. This scheme, adopted from production payment platforms (Stripe, Recurly, Paddle), provides:

- **Self-documenting versions**: the version string tells you exactly when a breaking change was introduced.
- **Lexicographic order == chronological order**: `"2025-01-01" > "2024-01-01"` is unambiguous.
- **No version sprawl**: each new version is a date, not an incrementing integer.

---

## Version Constants

| Constant | Value | Description |
|---|---|---|
| `ApiVersion.V_2024_01` | `2024-01-01` | Baseline version shipped at platform launch |
| `ApiVersion.V_2025_01` | `2025-01-01` | Hardened concurrency, idempotency, and ledger guarantees |
| `ApiVersion.CURRENT` | `V_2025_01` | Current production version |
| `ApiVersion.DEFAULT` | `V_2024_01` | Served when no version header is present |

---

## Header

Version is communicated via the `X-API-Version` request header:

```http
X-API-Version: 2025-01-01
```

The `ApiVersionFilter` reads this header at `HIGHEST_PRECEDENCE + 2`, stores the value in both MDC and `RequestContextHolder`, and makes it available to services through `ApiVersionContext.currentOrDefault()`.

---

## Three-Tier Resolution Precedence

When determining the effective API version for a request, the `ApiVersionedMapper` applies the following precedence hierarchy:

```
Tier 1: X-API-Version header        (client explicit — always wins)
           ↓ if absent/blank
Tier 2: Merchant-pinned version      (from merchant_api_versions table)
           ↓ if no pin configured
Tier 3: ApiVersion.DEFAULT           (platform fallback — "2024-01-01")
```

### Usage in code

```java
// Inject ApiVersionedMapper into your service/controller
ApiVersion effective = apiVersionedMapper.resolveEffectiveVersion(
    ctx.getMerchantId(),
    ctx.getApiVersion()   // raw header string, may be null
);

if (effective.isAfterOrEqual(ApiVersion.V_2025_01)) {
    return enrichedResponse(entity);
}
return legacyResponse(entity);
```

---

## Merchant Version Pinning

Merchants can be pinned to a specific API version. This is useful when:
- A merchant has not updated their integration and you don't want to force a breaking change.
- You want to roll out a new version to some merchants first (progressive delivery).

### API

| Method | Path | Description |
|---|---|---|
| `PUT` | `/merchants/{merchantId}/api-version` | Pin or update a merchant's version |
| `GET` | `/merchants/{merchantId}/api-version` | Read the current pin |
| `DELETE` | `/merchants/{merchantId}/api-version` | Remove the pin (reverts to DEFAULT) |

**Request body (PUT):**

```json
{
  "version": "2025-01-01",
  "effectiveFrom": "2025-06-01"
}
```

`effectiveFrom` is optional and defaults to today.

### Pin storage

Pins are stored in the `merchant_api_versions` table (created in migration V68):

```sql
CREATE TABLE merchant_api_versions (
    id              BIGSERIAL    PRIMARY KEY,
    merchant_id     BIGINT       NOT NULL,
    pinned_version  VARCHAR(20)  NOT NULL,
    effective_from  DATE         NOT NULL DEFAULT CURRENT_DATE,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_merchant_api_versions_merchant_id UNIQUE (merchant_id)
);
```

---

## Adding a New Version

1. Add a constant to `ApiVersion.java`:
   ```java
   public static final ApiVersion V_2026_01 = new ApiVersion("2026-01-01");
   ```
2. Update `CURRENT`:
   ```java
   public static final ApiVersion CURRENT = V_2026_01;
   ```
3. Implement version-gated logic in affected controllers/services using
   `isAfterOrEqual(ApiVersion.V_2026_01)`.
4. Document the new version (what changed, what is deprecated) in this file.
5. Communicate the sunset date for old versions to API consumers.

---

## Sunset Policy

| Version | Status | Sunset Date |
|---|---|---|
| `2024-01-01` | Supported (DEFAULT) | TBD |
| `2025-01-01` | Active (CURRENT) | — |

Versions are never removed without a minimum 12-month notice period.

---

## Version-Gated Response Example

When a service must return different data shapes for different versions:

```java
@GetMapping("/{id}")
public ResponseEntity<?> getSubscription(@PathVariable Long id) {
    Subscription sub = subscriptionService.findById(id);
    ApiVersion requested = ApiVersionContext.currentOrDefault();

    if (requested.isAfterOrEqual(ApiVersion.V_2025_01)) {
        return ResponseEntity.ok(SubscriptionDTOV2.from(sub)); // enriched
    }
    return ResponseEntity.ok(SubscriptionDTO.from(sub));       // legacy
}
```
