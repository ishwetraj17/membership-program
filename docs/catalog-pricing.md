# Catalog & Pricing Domain

> **Package root:** `com.firstclub.catalog`
> **Base URL:** `/api/v2/merchants/{merchantId}/…`

---

## Overview

The catalog domain introduces a three-level pricing model that replaces the flat `MembershipPlan` concept:

```
Merchant
 └── Product            (what you're selling)
      └── Price         (how you charge for the product — billing cadence + base amount)
           └── PriceVersion  (a historical or future snapshot of the price amount)
```

This decoupling lets you:
- Sell the same product at different price points (monthly vs. annual)
- Change pricing over time without invalidating historical invoices
- Schedule future price increases before they take effect
- Grandfather existing subscribers when a price changes

---

## Concepts

### Product

A product represents a distinct offering (e.g. "Gold Membership"). It lives under a merchant and has a stable `productCode` that is unique per merchant.

| State | Transitions |
|-------|-------------|
| `ACTIVE` | May be archived → `ARCHIVED` |
| `ARCHIVED` | Terminal — cannot be restored |

Archiving a product does **not** automatically deactivate its prices, but no new subscriptions should reference an archived product.

### Price

A price describes *how* a product is billed. Every price belongs to exactly one product and one merchant.

| Field | Notes |
|-------|-------|
| `billingType` | `RECURRING` or `ONE_TIME` |
| `currency` | ISO 4217 three-letter code (e.g. `INR`, `USD`) |
| `amount` | Precision up to 4 decimal places (DECIMAL 18,4) |
| `billingIntervalUnit` | `DAY`, `WEEK`, `MONTH`, `YEAR` — required for recurring |
| `billingIntervalCount` | Must be ≥ 1 for recurring |
| `trialDays` | Free trial period; defaults to 0 |
| `active` | Soft-deactivation; idempotent |

**Validation rules:**
- `RECURRING` prices **must** supply `billingIntervalUnit` and `billingIntervalCount ≥ 1`.
- `ONE_TIME` prices default `billingIntervalUnit = MONTH`, `billingIntervalCount = 1` when omitted.
- `priceCode` is unique per merchant (not per product).

Only `trialDays` is mutable after creation. Amount changes must go through a `PriceVersion`.

### PriceVersion

A PriceVersion is an immutable snapshot of a price's amount at a specific point in time. It represents the source of truth for billing — historical invoices always reference the version that was active at the time of billing.

| Field | Notes |
|-------|-------|
| `effectiveFrom` | When this version becomes active. Must not be in the past (1-minute clock-skew leeway). |
| `effectiveTo` | Exclusive end timestamp. `null` = open-ended (active indefinitely). Set automatically when a later version is created. |
| `amount` | Overrides the price's base amount for this window |
| `currency` | Can differ from the price's currency (rare) |
| `grandfatherExistingSubscriptions` | Flag — if `true`, the billing engine should preserve the previous rate for subscriptions created before this version takes effect |

**Rules:**
- Versions are **immutable** once created (no `updatedAt` column).
- Creating a new version automatically closes the previous open-ended version by setting its `effectiveTo = newVersion.effectiveFrom`.
- Overlapping version windows are rejected with `409 CONFLICT`.
- `effectiveFrom` in the past (beyond 1-minute clock skew) is rejected with `400`.

---

## API Reference

### Products

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| `POST` | `/api/v2/merchants/{mId}/products` | Create product | 201 |
| `GET` | `/api/v2/merchants/{mId}/products` | List products (paginated, `?status=ACTIVE`) | 200 |
| `GET` | `/api/v2/merchants/{mId}/products/{productId}` | Get product | 200 |
| `PUT` | `/api/v2/merchants/{mId}/products/{productId}` | Update name/description | 200 |
| `POST` | `/api/v2/merchants/{mId}/products/{productId}/archive` | Archive product | 200 |

### Prices

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| `POST` | `/api/v2/merchants/{mId}/prices` | Create price | 201 |
| `GET` | `/api/v2/merchants/{mId}/prices` | List prices (paginated, `?active=true`) | 200 |
| `GET` | `/api/v2/merchants/{mId}/prices/{priceId}` | Get price | 200 |
| `PUT` | `/api/v2/merchants/{mId}/prices/{priceId}` | Update trialDays | 200 |
| `POST` | `/api/v2/merchants/{mId}/prices/{priceId}/deactivate` | Deactivate price (idempotent) | 200 |
| `POST` | `/api/v2/merchants/{mId}/prices/{priceId}/versions` | Create price version | 201 |
| `GET` | `/api/v2/merchants/{mId}/prices/{priceId}/versions` | List versions (newest first) | 200 |

All endpoints require `ADMIN` role (`Authorization: Bearer <jwt>`).

---

## Database Schema

### `products`
```sql
id              BIGSERIAL PRIMARY KEY
merchant_id     BIGINT NOT NULL REFERENCES merchants(id)
product_code    VARCHAR(64) NOT NULL
name            VARCHAR(255) NOT NULL
description     TEXT
status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
created_at      TIMESTAMP NOT NULL DEFAULT now()
updated_at      TIMESTAMP NOT NULL DEFAULT now()
UNIQUE (merchant_id, product_code)
```

### `prices`
```sql
id                      BIGSERIAL PRIMARY KEY
merchant_id             BIGINT NOT NULL REFERENCES merchants(id)
product_id              BIGINT NOT NULL REFERENCES products(id)
price_code              VARCHAR(64) NOT NULL
billing_type            VARCHAR(32) NOT NULL
currency                VARCHAR(10) NOT NULL
amount                  DECIMAL(18,4) NOT NULL
billing_interval_unit   VARCHAR(16)
billing_interval_count  INT
trial_days              INT NOT NULL DEFAULT 0
active                  BOOLEAN NOT NULL DEFAULT TRUE
created_at              TIMESTAMP NOT NULL DEFAULT now()
updated_at              TIMESTAMP NOT NULL DEFAULT now()
UNIQUE (merchant_id, price_code)
```

### `price_versions`
```sql
id                                  BIGSERIAL PRIMARY KEY
price_id                            BIGINT NOT NULL REFERENCES prices(id)
effective_from                      TIMESTAMP NOT NULL
effective_to                        TIMESTAMP             -- NULL = open-ended
amount                              DECIMAL(18,4) NOT NULL
currency                            VARCHAR(10) NOT NULL
grandfather_existing_subscriptions  BOOLEAN NOT NULL DEFAULT FALSE
created_at                          TIMESTAMP NOT NULL DEFAULT now()
-- No updated_at; rows are immutable
```

---

## Tenant Isolation

Every query includes `merchantId` to prevent cross-merchant data leakage. All service methods call `loadMerchantOrThrow` to validate the merchant exists and then scope every repository lookup by `merchantId`. A 404 is returned if the resource doesn't exist within the requesting merchant's scope.

---

## Error Reference

| Code | HTTP | Meaning |
|------|------|---------|
| `PRODUCT_NOT_FOUND` | 404 | Product doesn't exist for this merchant |
| `DUPLICATE_PRODUCT_CODE` | 409 | `productCode` already taken in this merchant |
| `PRODUCT_ARCHIVED` | 400 | Operation not allowed on archived product |
| `PRICE_NOT_FOUND` | 404 | Price doesn't exist for this merchant |
| `DUPLICATE_PRICE_CODE` | 409 | `priceCode` already taken in this merchant |
| `PRICE_INACTIVE` | 400 | Operation not allowed on inactive price |
| `INVALID_BILLING_INTERVAL` | 400 | RECURRING price missing unit/count |
| `PRICE_VERSION_NOT_FOUND` | 404 | Version doesn't exist |
| `OVERLAPPING_PRICE_VERSION` | 409 | New version's window overlaps an existing one |
| `EFFECTIVE_FROM_IN_PAST` | 400 | `effectiveFrom` is more than 1 minute in the past |

---

## Relationship to Legacy Plan Model

The old `MembershipPlan` / `MembershipTier` model under `com.firstclub.membership` is intentionally preserved. It continues to power any existing subscriptions. The new catalog model runs alongside it and is the recommended path for all new integrations.
