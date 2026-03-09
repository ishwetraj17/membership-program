# Tenant / Merchant Model

## Overview

Phase 1 of the platform evolution introduces **merchant accounts** as the primary tenant
boundary. Every operational resource in subsequent phases (subscriptions, invoices, payments,
ledger entries) will carry a `merchant_id` foreign key, enabling strict data isolation and
per-tenant configuration.

---

## Entity Relationship

```
merchant_accounts (1)
    ├── merchant_users (0..N)  ─── users (N)  [many-to-many with role]
    └── merchant_settings (1)
```

### `merchant_accounts`

The root of the tenant hierarchy. Represents a legal business entity that operates on
the platform.

| Column             | Type          | Notes                                       |
|--------------------|---------------|---------------------------------------------|
| `id`               | BIGSERIAL PK  | Internal surrogate key                      |
| `merchant_code`    | VARCHAR(64)   | Immutable business key (`^[A-Z0-9_]{2,64}$`) |
| `legal_name`       | VARCHAR(255)  | Registered legal name                       |
| `display_name`     | VARCHAR(255)  | Public-facing name                          |
| `status`           | VARCHAR(20)   | Lifecycle state (see below)                 |
| `default_currency` | VARCHAR(3)    | ISO 4217 (default: INR)                     |
| `country_code`     | VARCHAR(2)    | ISO 3166-1 alpha-2 (default: IN)            |
| `timezone`         | VARCHAR(50)   | IANA timezone (default: Asia/Kolkata)       |
| `support_email`    | VARCHAR(255)  | Validated email for customer contact        |
| `created_at`       | TIMESTAMPTZ   | Immutable, set on INSERT                    |
| `updated_at`       | TIMESTAMPTZ   | Updated on every write                      |

### `merchant_users`

Junction table that assigns platform users to merchants with a role.

| Column        | Notes                                  |
|---------------|----------------------------------------|
| `merchant_id` | FK → `merchant_accounts(id)`           |
| `user_id`     | FK → `users(id)`                       |
| `role`        | `OWNER | ADMIN | OPERATIONS | SUPPORT | READ_ONLY` |

Business rules:
- A user may not be assigned to the same merchant twice.
- Every merchant must retain at least one `OWNER` — the last owner cannot be removed.

### `merchant_settings`

1-to-1 configuration record, auto-created with defaults when a merchant is first created.

| Column                   | Default   | Notes                          |
|--------------------------|-----------|--------------------------------|
| `webhook_enabled`        | TRUE      | Toggle webhook delivery        |
| `settlement_frequency`   | DAILY     | `DAILY | T_PLUS_1 | WEEKLY`    |
| `auto_retry_enabled`     | TRUE      | Dunning auto-retry             |
| `default_grace_days`     | 7         | Days before marking overdue    |
| `default_dunning_policy_code` | NULL | If set, overrides global policy |
| `metadata_json`          | NULL      | Arbitrary JSON extension point |

---

## Status Lifecycle

Merchant status transitions are validated by `StateMachineValidator` under the key
`"MERCHANT"`.

```
        ┌─────────┐
        │ PENDING │ ─── CREATE (all merchants start here)
        └────┬────┘
            │  ACTIVE / CLOSED
            ▼
        ┌────────┐
        │ ACTIVE │ ◄────────────────────┐
        └────┬───┘                      │
             │  SUSPENDED / CLOSED      │ ACTIVE (re-activate)
             ▼                          │
        ┌───────────┐ ─── ACTIVE ───────┘
        │ SUSPENDED │
        └─────┬─────┘
              │  CLOSED
              ▼
          ┌────────┐
          │ CLOSED │  ← terminal, no further transitions
          └────────┘
```

Allowed transitions (as registered in `StateMachineValidator.registerMerchantRules`):

| From      | Allowed Targets           |
|-----------|---------------------------|
| PENDING   | ACTIVE, CLOSED            |
| ACTIVE    | SUSPENDED, CLOSED         |
| SUSPENDED | ACTIVE, CLOSED            |
| CLOSED    | *(terminal — none)*       |

---

## API Endpoints

All endpoints require `ROLE_ADMIN`. Base path: `/api/v2/admin/merchants`.

| Method | Path                        | Status | Description                    |
|--------|-----------------------------|--------|--------------------------------|
| POST   | `/`                         | 201    | Create merchant (→ PENDING)    |
| GET    | `/`                         | 200    | Paginated list (optional ?status filter) |
| GET    | `/{id}`                     | 200    | Get by ID                      |
| PUT    | `/{id}`                     | 200    | Update mutable fields          |
| PUT    | `/{id}/status`              | 200    | Status transition              |
| POST   | `/{id}/users`               | 201    | Add user to merchant           |
| GET    | `/{id}/users`               | 200    | List merchant users            |
| DELETE | `/{id}/users/{userId}`      | 204    | Remove user from merchant      |

---

## Design Decisions

### `merchantCode` is immutable
The merchant code is set once at creation and never changes. It serves as the stable
external identifier for integrations, webhooks, and audit logs, where `id` (a surrogate
integer) should not be embedded in external contracts.

### Settings auto-created on merchant creation
`MerchantSettings` are created with platform defaults via `MerchantServiceImpl.createMerchant`
immediately after the account is saved. Callers never need to POST a separate settings
resource — the settings record is always present and can be updated in place.

### `merchant_id` on all operational records (future phases)
Starting Phase 2, every entity that carries business data (subscriptions, invoices,
payments, ledger entries, etc.) will have a non-nullable `merchant_id` column.
`MerchantService.validateMerchantActive(merchantId)` is the single gate that downstream
services should call before accepting any operation for a tenant.

### Why `PostgresIntegrationTestBase` uses `create-drop`
Integration tests run against a live PostgreSQL container (Testcontainers). Flyway is
disabled and the schema is created from JPA entities. This keeps tests self-contained
and independent of migration ordering.

---

## Package Layout

```
com.firstclub.merchant/
├── controller/
│   ├── MerchantAdminController.java       # /api/v2/admin/merchants
│   └── MerchantUserAdminController.java   # /api/v2/admin/merchants/{id}/users
├── dto/
│   ├── MerchantCreateRequestDTO.java
│   ├── MerchantUpdateRequestDTO.java
│   ├── MerchantStatusUpdateRequestDTO.java
│   ├── MerchantResponseDTO.java
│   ├── MerchantSettingsDTO.java
│   ├── MerchantUserCreateRequestDTO.java
│   └── MerchantUserResponseDTO.java
├── entity/
│   ├── MerchantAccount.java
│   ├── MerchantSettings.java
│   ├── MerchantUser.java
│   ├── MerchantStatus.java           (enum)
│   ├── MerchantUserRole.java         (enum)
│   └── SettlementFrequency.java      (enum)
├── exception/
│   └── MerchantException.java
├── mapper/
│   ├── MerchantMapper.java
│   └── MerchantUserMapper.java
├── repository/
│   ├── MerchantAccountRepository.java
│   ├── MerchantSettingsRepository.java
│   └── MerchantUserRepository.java
└── service/
    ├── MerchantService.java
    ├── MerchantUserService.java
    └── impl/
        ├── MerchantServiceImpl.java
        └── MerchantUserServiceImpl.java
```
