# Payment Methods & Mandates

## Overview

Phase 5 introduces reusable, tokenized payment instruments per customer. Instead of one-off
payment credentials, merchants can store opaque **provider tokens** (issued by the payment
gateway) for later use in subscriptions and recurring charges.

**Security:** Raw card PANs, CVVs, and full account numbers are **never stored**. Only the
opaque `providerToken` issued by the gateway, plus non-sensitive display metadata (`last4`,
`brand`), are persisted.

---

## Domain Model

### Tables

```
payment_methods
├── id              BIGSERIAL PK
├── merchant_id     FK → merchant_accounts
├── customer_id     FK → customers
├── method_type     ENUM (CARD | UPI | NETBANKING | WALLET | MANDATE)
├── provider_token  VARCHAR(255)  -- opaque gateway token, NEVER raw PAN
├── fingerprint     VARCHAR(255)  -- optional stable hash for deduplication
├── last4           VARCHAR(8)    -- display only
├── brand           VARCHAR(64)   -- display only (Visa, Mastercard, etc.)
├── provider        VARCHAR(64)   -- e.g. "razorpay", "stripe"
├── status          ENUM (ACTIVE | INACTIVE | EXPIRED | REVOKED) DEFAULT ACTIVE
├── is_default      BOOLEAN DEFAULT false
├── created_at      TIMESTAMP
└── updated_at      TIMESTAMP

payment_method_mandates
├── id                  BIGSERIAL PK
├── payment_method_id   FK → payment_methods
├── mandate_reference   VARCHAR(128)  -- gateway mandate ID / NACH reference
├── status              ENUM (PENDING | ACTIVE | FAILED | REVOKED) DEFAULT PENDING
├── max_amount          DECIMAL(18,4)
├── currency            VARCHAR(10)
├── approved_at         TIMESTAMP nullable
├── revoked_at          TIMESTAMP nullable
└── created_at          TIMESTAMP
```

### Unique constraints

| Constraint | Columns | Purpose |
|---|---|---|
| `uq_payment_methods_provider_token` | `(provider, provider_token)` | Prevent double-registration of the same gateway token |

---

## Enums

### `PaymentMethodType`

| Value | Description | Supports Mandates |
|---|---|---|
| `CARD` | Tokenized credit/debit card | Yes |
| `UPI` | UPI VPA (e.g. +91-user@upi) | No |
| `NETBANKING` | Net banking token | No |
| `WALLET` | Wallet instrument | No |
| `MANDATE` | Standalone mandate instrument | Yes |

### `PaymentMethodStatus`

| Value | `isUsable()` | Notes |
|---|---|---|
| `ACTIVE` | `true` | Normal, usable state |
| `INACTIVE` | `false` | Temporarily disabled |
| `EXPIRED` | `false` | Gateway token expired |
| `REVOKED` | `false` | Permanently revoked, cannot be reactivated |

### `MandateStatus`

| Value | `isActionable()` | Notes |
|---|---|---|
| `PENDING` | `false` | Awaiting gateway activation |
| `ACTIVE` | `true` | Approved and chargeable |
| `FAILED` | `false` | Gateway rejected |
| `REVOKED` | `false` | Cancelled |

---

## API Reference

**Base path:** `/api/v2/merchants/{merchantId}/customers/{customerId}/payment-methods`

All endpoints require `Authorization: Bearer <token>` with `ADMIN` role.

### Payment Methods

#### Register a payment method

```
POST /api/v2/merchants/{merchantId}/customers/{customerId}/payment-methods
```

**Request body:**

```json
{
  "methodType": "CARD",
  "providerToken": "tok_visa_xxxxxxxxxxxx",
  "provider": "razorpay",
  "fingerprint": "fp_abc123",
  "last4": "4242",
  "brand": "Visa",
  "makeDefault": true
}
```

- The first payment method for a customer is **automatically set as default** regardless of `makeDefault`.
- If `makeDefault: true`, any existing default is cleared in the same transaction.
- Returns **201 Created** with the created payment method.

#### List customer payment methods

```
GET /api/v2/merchants/{merchantId}/customers/{customerId}/payment-methods
```

Returns **200 OK** with an array of all payment method objects (any status).

#### Set default payment method

```
POST /api/v2/merchants/{merchantId}/customers/{customerId}/payment-methods/{paymentMethodId}/default
```

- Only `ACTIVE` payment methods can be set as default.
- Clears the previous default atomically.
- Returns **200 OK** with the updated payment method.

#### Revoke a payment method

```
DELETE /api/v2/merchants/{merchantId}/customers/{customerId}/payment-methods/{paymentMethodId}
```

- Sets status to `REVOKED`. This is **permanent** — revoked methods cannot be reactivated.
- If the revoked method was the default, `isDefault` is cleared.
- Returns **200 OK** with the revoked payment method.

---

### Payment Method Mandates

**Base path:** `/api/v2/merchants/{merchantId}/customers/{customerId}/payment-methods/{paymentMethodId}/mandates`

Mandates represent standing instructions for recurring debits (e.g. NACH/ECS in India).
Only **CARD** and **MANDATE** type payment methods support mandates.

#### Create a mandate

```
POST .../{paymentMethodId}/mandates
```

**Request body:**

```json
{
  "mandateReference": "NACH_REF_20240101_00001",
  "maxAmount": "5000.0000",
  "currency": "INR"
}
```

- The mandate is created with `PENDING` status.
- Requires the payment method to be `ACTIVE`.
- Returns **201 Created**.

#### List mandates

```
GET .../{paymentMethodId}/mandates
```

Returns **200 OK** with mandates ordered by `createdAt` descending.

#### Revoke a mandate

```
POST .../{paymentMethodId}/mandates/{mandateId}/revoke
```

- Sets status to `REVOKED` and records `revokedAt` timestamp.
- Already-revoked mandates return **400**.
- Returns **200 OK**.

---

## Business Rules

1. **No raw PAN** — `providerToken` must be an opaque gateway-issued token. The API has no field for raw card numbers (by design).

2. **Single default per customer** — `setDefault` and `createPaymentMethod(makeDefault=true)` both clear any existing default in the same `@Transactional` block.

3. **Usability gate** — REVOKED, EXPIRED, or INACTIVE methods cannot become the default or have mandates created on them.

4. **Mandate type restriction** — Only `CARD` and `MANDATE` type methods return `supportsMandates() = true`. Attempting to create a mandate on `UPI`, `NETBANKING`, or `WALLET` returns **400 UNSUPPORTED_MANDATE_METHOD_TYPE**.

5. **Tenant isolation** — Every repository query is scoped by `merchantId`. Cross-merchant access returns **404**.

6. **Duplicate token prevention** — The `(provider, provider_token)` pair is enforced unique at the DB level and checked in service before insert.

---

## Error Codes

| Code | HTTP | Description |
|---|---|---|
| `PAYMENT_METHOD_NOT_FOUND` | 404 | Payment method not found for given merchant + customer |
| `DUPLICATE_PROVIDER_TOKEN` | 409 | Provider token already registered with this provider |
| `PAYMENT_METHOD_NOT_USABLE` | 400 | Method is REVOKED / EXPIRED / INACTIVE |
| `UNSUPPORTED_MANDATE_METHOD_TYPE` | 400 | Method type does not support mandates |
| `MANDATE_NOT_FOUND` | 404 | Mandate not found for given payment method |
| `MANDATE_ALREADY_REVOKED` | 400 | Mandate is already in REVOKED status |

---

## Implementation Notes

- **Flyway migration:** `V19__payment_methods_and_mandates.sql`
- **Package:** `com.firstclub.payments.*` (extends existing payments package)
- **MapStruct mappers:** `PaymentMethodMapper`, `PaymentMethodMandateMapper`
- **No PAN fields:** the `PaymentMethodCreateRequestDTO` intentionally has no card number field — this is enforced by the absence of such a field, not by validation alone.
- **Idempotency for `setDefault`:** if the requested method is already the default, the operation is a no-op (no write occurs).
