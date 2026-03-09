# Customer Domain

## Overview

Phase 2 introduces the **Customer** domain — a merchant-scoped billable identity that is explicitly separate from the platform's authenticated **User** (operator/admin) identity.

---

## User vs Customer: A Critical Distinction

| Aspect | `User` (platform operator) | `Customer` (merchant customer) |
|---|---|---|
| Package | `com.firstclub.membership.entity` | `com.firstclub.customer.entity` |
| Authentication | Has platform credentials (email + BCrypt password + JWT) | No platform credentials |
| Scope | Global (can operate across merchants) | Tenant-scoped (belongs to exactly one merchant) |
| Purpose | Logs in; manages API resources; author of notes | Billable identity; subject of subscriptions/invoices |
| Email uniqueness | Globally unique | Unique **per merchant** only |
| PII encryption | Phone number encrypted (AES-256-GCM) | Phone, billing address, shipping address encrypted |

> **Rule of thumb:** When you see `User`, think "who is operating the system." When you see `Customer`, think "who is being billed."

---

## Tenant Scope

Every `Customer` belongs to exactly one `MerchantAccount` (the tenant).

- The `merchant_id` foreign key is **immutable after creation**.
- All service methods and repository queries include `merchantId` as a parameter — tenant isolation is enforced at the **service layer**, not only via URL path validation.
- Cross-merchant reads always return `404 NOT FOUND` — never `403 FORBIDDEN` — to avoid leaking entity existence to callers who happen to know a foreign customer ID.

### Email Uniqueness

- Email is unique **within a merchant**: the same `alice@example.com` can exist as a customer in Merchant A and also in Merchant B.
- Email is normalised to lower-case before persistence and uniqueness checks.
- The database enforces this via `UNIQUE (merchant_id, email)`.

### External Customer ID

- Optional field allowing merchants to bring their own CRM identifier.
- When provided, must be unique within the merchant (partial unique index handles `NULL` correctly in Postgres).

---

## Entity Schema

### `customers`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `merchant_id` | BIGINT FK → merchant_accounts | tenant key; immutable |
| `external_customer_id` | VARCHAR(128) | optional; unique per merchant |
| `email` | VARCHAR(255) NOT NULL | unique per merchant; lower-case |
| `phone` | VARCHAR(1024) | AES-256-GCM encrypted |
| `full_name` | VARCHAR(255) NOT NULL | |
| `billing_address` | TEXT | AES-256-GCM encrypted |
| `shipping_address` | TEXT | AES-256-GCM encrypted |
| `status` | VARCHAR(32) NOT NULL | `ACTIVE` / `INACTIVE` / `BLOCKED` |
| `default_payment_method_id` | BIGINT | nullable; FK deferred to payment_methods phase |
| `metadata_json` | TEXT | arbitrary key-value metadata |
| `created_at` | TIMESTAMP NOT NULL | |
| `updated_at` | TIMESTAMP NOT NULL | |

### `customer_notes`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `customer_id` | BIGINT FK → customers | |
| `author_user_id` | BIGINT FK → users | platform User who wrote the note |
| `note_text` | TEXT NOT NULL | |
| `visibility` | VARCHAR(32) NOT NULL | `INTERNAL_ONLY` / `MERCHANT_VISIBLE` |
| `created_at` | TIMESTAMP NOT NULL | no `updated_at` — notes are immutable |

---

## Customer Status Lifecycle

```
ACTIVE ──────────────────┬──────────┐
  ↑                       │          │
  │ (activate)       (block)    (deactivate)
  │                       │          │
BLOCKED ◄── (block) ── INACTIVE ─────┘
  │
  └─── (activate) → ACTIVE
```

Transitions registered in `StateMachineValidator` (key `"CUSTOMER"`):

| From | Allowed → |
|---|---|
| `ACTIVE` | `INACTIVE`, `BLOCKED` |
| `INACTIVE` | `ACTIVE`, `BLOCKED` |
| `BLOCKED` | `ACTIVE` |

Unlike merchant/subscription status transitions, customer status is managed directly via `/block` and `/activate` endpoints rather than a generic status update DTO — this makes the API intent explicit and avoids ambiguous state requests.

---

## PII Encryption

Three fields on `Customer` are stored as AES-256-GCM ciphertext using the platform's `EncryptedStringConverter` JPA attribute converter:

- `phone`
- `billingAddress`
- `shippingAddress`

The encryption key is provisioned via the `PII_ENC_KEY` environment variable (Base64-encoded 256-bit key). In development/test environments, the converter falls back to a deterministic test key so unit tests run without external configuration.

Encrypted values are transparently decrypted when the entity is read from the database — callers receive plaintext in API responses.

---

## API Endpoints

All endpoints require `ROLE_ADMIN` and are merchant-scoped via `{merchantId}`.

### Customer CRUD

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v2/merchants/{merchantId}/customers` | Create customer (201) |
| `GET` | `/api/v2/merchants/{merchantId}/customers` | List customers (paginated, filterable by `status`) |
| `GET` | `/api/v2/merchants/{merchantId}/customers/{customerId}` | Get single customer |
| `PUT` | `/api/v2/merchants/{merchantId}/customers/{customerId}` | Update mutable fields (patch semantics) |
| `POST` | `/api/v2/merchants/{merchantId}/customers/{customerId}/block` | Block customer |
| `POST` | `/api/v2/merchants/{merchantId}/customers/{customerId}/activate` | Activate customer |

### Customer Notes

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v2/merchants/{merchantId}/customers/{customerId}/notes` | Add note (201) |
| `GET` | `/api/v2/merchants/{merchantId}/customers/{customerId}/notes` | List notes (newest first) |

---

## Customer Notes Usage

Notes are **immutable audit-trail entries**. Once written, a note cannot be edited or hard-deleted. The recommended approach for "hiding" an erroneous note is to change its visibility to `INTERNAL_ONLY` via a future admin API (not implemented in Phase 2).

### Visibility

- `INTERNAL_ONLY` — visible only to platform operators and admins.
- `MERCHANT_VISIBLE` — visible to the merchant's own staff in merchant-facing UIs.

### Author

The note author is automatically resolved from the JWT-authenticated principal via `AuditContext.getCurrentUserId()`. The caller does not pass `authorUserId` in the request body — it is derived from the security context.

---

## Package Layout

```
com.firstclub.customer/
├── controller/
│   ├── CustomerController.java           # /api/v2/merchants/{id}/customers
│   └── CustomerNoteController.java       # /api/v2/merchants/{id}/customers/{id}/notes
├── dto/
│   ├── CustomerCreateRequestDTO.java
│   ├── CustomerUpdateRequestDTO.java
│   ├── CustomerResponseDTO.java
│   ├── CustomerNoteCreateRequestDTO.java
│   └── CustomerNoteResponseDTO.java
├── entity/
│   ├── Customer.java
│   ├── CustomerNote.java
│   ├── CustomerStatus.java               # ACTIVE, INACTIVE, BLOCKED
│   └── CustomerNoteVisibility.java       # INTERNAL_ONLY, MERCHANT_VISIBLE
├── exception/
│   └── CustomerException.java
├── mapper/
│   ├── CustomerMapper.java               # MapStruct
│   └── CustomerNoteMapper.java           # MapStruct
├── repository/
│   ├── CustomerRepository.java
│   └── CustomerNoteRepository.java
└── service/
    ├── CustomerService.java
    ├── CustomerNoteService.java
    └── impl/
        ├── CustomerServiceImpl.java
        └── CustomerNoteServiceImpl.java
```

---

## Why This Matters for Fintech Platform Design

### Separation of Concerns in Identity

A recurring antipattern in early-stage fintech platforms is conflating "who can log in" with "who can be billed." When a single `User` entity plays both roles:

- Email uniqueness becomes a cross-tenant constraint, breaking multi-tenant onboarding.
- Payment, subscription, and invoice tables get polluted with auth-related fields.
- Security review becomes harder: PII for billable customers ends up co-located with authentication hashes.

The `User`/`Customer` split cleanly separates these concerns from Phase 2 onward.

### Tenant Isolation from Day One

By including `merchant_id` in every repository query method (not relying solely on URL path params), the service layer enforces isolation even if a controller bug passes the wrong `merchantId`. This is a defence-in-depth pattern critical for multi-tenant SaaS platforms.

### Deferred Payment Method FK

The `default_payment_method_id` column exists on `customers` but the FK constraint to `payment_methods` is intentionally deferred. Introducing the FK now would create a circular dependency risk between Phase 2 (customers) and a future phase (payment methods). This is a common DDL ordering problem in iterative schema design — the FK will be added in the payment methods migration.
