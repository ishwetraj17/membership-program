# Merchant API Keys — Phase 19

## Overview

Phase 19 introduces **merchant API keys**, **access scopes**, and **sandbox/live mode management**. Together these form the foundation for merchants to consume the FirstClub platform programmatically, with clear data isolation between test (sandbox) and production (live) environments.

---

## Key Lifecycle

```
                   ┌─────────────────────────────────┐
                   │  POST /api-keys                  │
                   │  ┌──────────────────────────────┐│
                   │  │ 1. Generate:                  ││
                   │  │    prefix  = fc_{sb|lv}_{16hex}││
                   │  │    secret  = {40 random hex} ││
                   │  │    rawKey  = prefix_secret    ││
                   │  │                               ││
                   │  │ 2. Store:                     ││
                   │  │    key_prefix  → DB (plaintext)││
                   │  │    key_hash    → SHA-256(rawKey)││
                   │  │    rawKey      → NEVER stored  ││
                   │  │                               ││
                   │  │ 3. Return rawKey ONCE in resp ││
                   │  └──────────────────────────────┘│
                   └─────────────────────────────────┘
                              │ ACTIVE
                              ▼
               ┌─────────────────────────────┐
               │  Authenticate (rawKey)       │
               │  1. prefix = rawKey[0:22]    │
               │  2. lookup by prefix (O(1))  │
               │  3. compare SHA-256(rawKey)  │
               │     with stored key_hash     │
               └─────────────────────────────┘
                              │
                    ┌─────────┴─────────┐
                 success             failure
                    │                   │
              updateLastUsed()     return empty
                              │
               ┌─────────────────────────────┐
               │  DELETE /{id}               │
               │  status → REVOKED           │
               │  Revoked keys cannot auth   │
               └─────────────────────────────┘
```

---

## Security Design

### Hash-Only Storage

The raw API key is **never written to the database**. Only these two fields are persisted for each key:

| Field       | Value                       | Purpose                  |
|-------------|----------------------------|--------------------------|
| `key_prefix` | `fc_sb_a1b2c3d4e5f6a7b8` | Fast O(1) prefix lookup  |
| `key_hash`  | `SHA-256(rawKey)` (64 hex) | Secure hash comparison   |

### Key Format

```
fc_{mode}_{16 random hex chars}_{40 random hex chars}
│   │      └── prefix random    └── secret (never stored)
│   └── sb (sandbox) or lv (live)
└── vendor prefix
```

Total length: **63 characters**.

### Authentication Flow

```
Authorization: ApiKey fc_sb_a1b2c3d4e5f6a7b8_<secret>
                       └─── prefix (22 chars) ──┘
                       └──────── full rawKey ──────────┘
```

1. Extract `rawKey.substring(0, 22)` as the prefix.
2. Look up `merchant_api_keys` by `key_prefix` (indexed).
3. If not found or `status = REVOKED` → reject.
4. Compute `SHA-256(rawKey)` and compare with `key_hash` using string equality.
5. On match → return the `MerchantApiKey`; update `last_used_at`.

### One-Time Secret Visibility

The `rawKey` field appears **only** in the `MerchantApiKeyCreateResponseDTO` returned from `POST /api-keys`.  It is never returned by `GET /api-keys` or any other endpoint.  The client must securely store it immediately.

---

## Access Scopes

Scopes are stored as a JSON array in `scopes_json`.  The available scopes are defined in `ApiScope`:

| Scope                  | Description                            |
|------------------------|----------------------------------------|
| `customers:read`       | Read customer profiles and history     |
| `customers:write`      | Create or update customers             |
| `subscriptions:read`   | Read subscription data                 |
| `subscriptions:write`  | Create or cancel subscriptions         |
| `invoices:read`        | Read invoices                          |
| `refunds:write`        | Initiate refunds                       |
| `payments:read`        | Read payment intents and attempts      |
| `payments:write`       | Create payment intents                 |

Future request interceptors can enforce scope checks per endpoint using `ApiKeyAuthenticator` (see below).

---

## Sandbox / Live Mode

### Intent

| Mode      | Intended Use                     | Default        |
|-----------|----------------------------------|----------------|
| `SANDBOX` | Development, testing, demos      | Enabled at creation |
| `LIVE`    | Production transactions          | Disabled; requires ACTIVE merchant |

### Configuration (merchant_modes table)

```
merchant_id    → FK to merchant_accounts (PK of this table)
sandbox_enabled → default TRUE
live_enabled    → default FALSE
default_mode    → SANDBOX | LIVE (must be an enabled mode)
updated_at      → auto-managed by @UpdateTimestamp
```

### Business Rules

- **Sandbox is always available** — new merchants start with sandbox enabled.
- **Live requires ACTIVE status** — calling `PUT /mode` with `liveEnabled=true` fails with `400` if the merchant's status is not `ACTIVE`.
- **defaultMode must be consistent** — setting `defaultMode=SANDBOX` when `sandboxEnabled=false`, or `defaultMode=LIVE` when `liveEnabled=false`, returns `400`.
- **Lazy initialisation** — `GET /mode` lazily creates the default config on first access; no setup step required.

---

## API Endpoints

### API Key Management

| Method   | Path                                            | Auth    | Description                                 |
|----------|-------------------------------------------------|---------|---------------------------------------------|
| `POST`   | `/api/v2/merchants/{merchantId}/api-keys`       | ADMIN   | Create key — `rawKey` visible in response only |
| `GET`    | `/api/v2/merchants/{merchantId}/api-keys`       | ADMIN   | List all keys (no secret material)          |
| `DELETE` | `/api/v2/merchants/{merchantId}/api-keys/{id}`  | ADMIN   | Revoke a key → `204 No Content`             |

### Mode Management

| Method | Path                                      | Auth  | Description                          |
|--------|-------------------------------------------|-------|--------------------------------------|
| `GET`  | `/api/v2/merchants/{merchantId}/mode`     | ADMIN | Get current sandbox/live config      |
| `PUT`  | `/api/v2/merchants/{merchantId}/mode`     | ADMIN | Update mode config                   |

---

## Auth Foundation Classes

### `ApiKeyAuthenticator`

A Spring `@Component` ready for use in a future security filter:

```java
// Future filter sketch
String header = request.getHeader("Authorization");
if (header != null && header.startsWith("ApiKey ")) {
    String rawKey = header.substring(7);
    apiKeyAuthenticator.authenticate(rawKey)
        .ifPresent(key -> {
            // key.getMerchantId()    → merchant identity
            // key.getMode()         → SANDBOX or LIVE
            // key.getScopesJson()   → JSON array of scopes
            SecurityContextHolder.getContext()
                .setAuthentication(new ApiKeyAuthenticationToken(key));
        });
}
```

### `ApiScope`

Constants class with all defined scope strings.  Use `ApiScope.ALL` to create a full-access key.

---

## Database Schema

```sql
CREATE TABLE merchant_api_keys (
    id           BIGSERIAL    PRIMARY KEY,
    merchant_id  BIGINT       NOT NULL REFERENCES merchant_accounts(id),
    key_prefix   VARCHAR(32)  NOT NULL,   -- stored plaintext for fast lookup
    key_hash     VARCHAR(255) NOT NULL,   -- SHA-256(rawKey), never the raw key
    mode         VARCHAR(16)  NOT NULL,   -- SANDBOX | LIVE
    scopes_json  TEXT         NOT NULL,   -- JSON array, e.g. ["customers:read"]
    status       VARCHAR(16)  NOT NULL,   -- ACTIVE | REVOKED
    last_used_at TIMESTAMP    NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE merchant_modes (
    merchant_id     BIGINT      PRIMARY KEY REFERENCES merchant_accounts(id),
    sandbox_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    live_enabled    BOOLEAN     NOT NULL DEFAULT FALSE,
    default_mode    VARCHAR(16) NOT NULL,  -- SANDBOX | LIVE
    updated_at      TIMESTAMP   NOT NULL DEFAULT NOW()
);
```

---

## Package Structure

```
com.firstclub.merchant.auth/
├── ApiKeyAuthenticator.java        ← authentication foundation
├── ApiScope.java                   ← scope constants
├── controller/
│   ├── MerchantApiKeyController.java
│   └── MerchantModeController.java
├── dto/
│   ├── MerchantApiKeyCreateRequestDTO.java
│   ├── MerchantApiKeyCreateResponseDTO.java  ← includes rawKey (once)
│   ├── MerchantApiKeyResponseDTO.java        ← no secret material
│   ├── MerchantModeResponseDTO.java
│   └── MerchantModeUpdateRequestDTO.java
├── entity/
│   ├── MerchantApiKey.java
│   ├── MerchantApiKeyMode.java     ← SANDBOX | LIVE
│   ├── MerchantApiKeyStatus.java   ← ACTIVE | REVOKED
│   └── MerchantMode.java
├── repository/
│   ├── MerchantApiKeyRepository.java
│   └── MerchantModeRepository.java
└── service/
    ├── MerchantApiKeyService.java  (interface)
    ├── MerchantModeService.java    (interface)
    └── impl/
        ├── MerchantApiKeyServiceImpl.java
        └── MerchantModeServiceImpl.java
```

---

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| SHA-256 for key hashing | Standard, available in Java SE without external deps; collision-resistant for this use case |
| Prefix stored plaintext | Enables O(1) DB lookup without full table scan; prefix is not secret (can't reverse to find secret) |
| Prefix length = 22 chars | `fc_{sb\|lv}_` (6) + 16 random hex (8 bytes) = 22; unique enough to avoid collisions, short enough to be readable |
| `SANDBOX` enabled by default | Zero-config for testing; merchants can start integrating immediately |
| Live mode gated on ACTIVE | Prevents sandbox-only merchants from accidentally processing real payments |
| `defaultMode` consistency validation | Prevents an impossible runtime state where the default mode is disabled |
| `ApiKeyAuthenticator` as `@Component` | Decoupled from filter chain; can be used in Spring Security or any filter, including future middleware |
