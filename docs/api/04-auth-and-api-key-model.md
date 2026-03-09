# Auth and API Key Model

This document describes how authentication, authorization, and API key management work in the FirstClub platform.

---

## Authentication Methods

The platform supports two authentication methods:

| Method | Header | Used By |
|---|---|---|
| JWT Bearer Token | `Authorization: Bearer {token}` | Customer-facing UI, merchant portal, server-side flows |
| API Key | `X-Api-Key: {key}` | Backend server-to-server integrations |

Both methods identify the caller and attach a role. A request must carry exactly one of these.

---

## JWT Authentication

### Token Format

Tokens are signed JWT (HS256 or RS256). The claims structure:

```json
{
  "sub": "user_uuid",
  "merchantId": "mer_abc123",
  "roles": ["MERCHANT"],
  "scope": ["payments:write", "subscriptions:read"],
  "iat": 1705310000,
  "exp": 1705396400,
  "jti": "jwt_unique_id"
}
```

| Claim | Description |
|---|---|
| `sub` | User UUID (customer or merchant user) |
| `merchantId` | Merchant UUID this token operates on behalf of |
| `roles` | Role list (see Roles section below) |
| `scope` | Fine-grained permission list |
| `iat` | Issued-at (Unix seconds) |
| `exp` | Expiry (Unix seconds); default 24 hours |
| `jti` | Unique JWT ID; used for revocation (future) |

### Token Lifecycle

1. Obtain token via: `POST /api/v1/auth/login` — returns `{accessToken, refreshToken}`
2. Use `accessToken` on every request
3. Refresh before expiry via: `POST /api/v1/auth/refresh` — returns new `accessToken`
4. On logout: `POST /api/v1/auth/logout` — invalidates refresh token

### Error Responses

| Scenario | HTTP | Code |
|---|---|---|
| No token provided | 401 | `UNAUTHORIZED` |
| Token expired | 401 | `TOKEN_EXPIRED` |
| Token signature invalid | 401 | `UNAUTHORIZED` |
| Token valid but insufficient role | 403 | `FORBIDDEN` |

---

## API Key Authentication

### Key Format

```
sk_live_XXXXXXXXXXXXXXXXXXXXXXXXXXXX
sk_test_XXXXXXXXXXXXXXXXXXXXXXXXXXXX
```

- Prefix `sk_live_` → production keys
- Prefix `sk_test_` → sandbox/test keys
- 32-character random suffix, URL-safe Base64 encoded
- Total length: 40 characters

### Key Storage

Keys are **not stored in plaintext**. Only the SHA-256 hash of the key is stored in `api_keys`. Whenever a key is used:

1. Server hashes the incoming `X-Api-Key` value
2. Looks up `api_keys.key_hash = SHA256(provided_key)`
3. Checks `status = ACTIVE` and `expires_at > NOW()`

The actual key value is shown **once** at creation time. It cannot be retrieved again.

### Key Lifecycle

**Create a key:**
```
POST /api/v1/merchants/{merchantId}/api-keys
Authorization: Bearer {adminToken}
Content-Type: application/json

{
  "name": "production-billing-server",
  "scopes": ["payments:write", "subscriptions:write", "refunds:write"],
  "expiresAt": null
}
```

Response includes the plaintext key (shown once only):
```json
{
  "id": "key_uuid",
  "name": "production-billing-server",
  "key": "sk_live_aBcDeFgH...",
  "prefix": "sk_live_aBcD",
  "scopes": ["payments:write", "subscriptions:write", "refunds:write"],
  "createdAt": "2024-01-15T10:30:00Z",
  "expiresAt": null
}
```

**List keys (prefix + metadata only, never full key):**
```
GET /api/v1/merchants/{merchantId}/api-keys
```

**Rotate a key:**
```
POST /api/v1/merchants/{merchantId}/api-keys/{keyId}/rotate
```
Generates a new key; the old key is immediately revoked.

**Revoke a key:**
```
DELETE /api/v1/merchants/{merchantId}/api-keys/{keyId}
```

---

## Roles

| Role | Who | Access |
|---|---|---|
| `MERCHANT` | Merchant server / portal user | Can manage their own subscriptions, invoices, payments, refunds, webhooks |
| `CUSTOMER` | End customer | Can view their own subscription status; cannot write |
| `OPERATOR` | Internal FirstClub ops engineer | Can access admin endpoints, trigger jobs, view all merchants |
| `ADMIN` | Internal FirstClub admin | Full access including financial repair actions and user management |

### Role Hierarchy

```
ADMIN > OPERATOR > MERCHANT > CUSTOMER
```

Higher roles include all permissions of lower roles on their own resources.

---

## Scopes

Scopes provide fine-grained access control within a role. API keys can be restricted to a subset of their role's maximum scope.

| Scope | Description |
|---|---|
| `subscriptions:read` | Read subscription data |
| `subscriptions:write` | Create, update, cancel subscriptions |
| `invoices:read` | Read invoice data |
| `invoices:write` | Create, void invoices |
| `payments:read` | Read payment data |
| `payments:write` | Create payment intents, confirm/capture |
| `refunds:read` | Read refund data |
| `refunds:write` | Request refunds |
| `webhooks:read` | Read webhook endpoint configuration |
| `webhooks:write` | Create, update, delete webhook endpoints |
| `reporting:read` | Access reconciliation and revenue reports |
| `admin:*` | All admin operations (ADMIN role only) |

---

## Rate Limiting

API keys are rate-limited independently. Current limits:

| Tier | Requests / second | Requests / minute | Requests / day |
|---|---|---|---|
| Default (MERCHANT) | 10 | 200 | 10,000 |
| Elevated (on request) | 50 | 1,000 | 100,000 |
| OPERATOR | 100 | 5,000 | Unlimited |
| ADMIN | Unlimited | Unlimited | Unlimited |

**Redis keys for rate limiting (planned):**
```
{env}:firstclub:rl:apikey:{merchantId}:{keyPrefix}:second
{env}:firstclub:rl:apikey:{merchantId}:{keyPrefix}:minute
{env}:firstclub:rl:apikey:{merchantId}:{keyPrefix}:day
```

Today, rate limiting is enforced at the application layer using a sliding window algorithm.

Rate limit headers included in all responses:
```
X-RateLimit-Limit: 200
X-RateLimit-Remaining: 147
X-RateLimit-Reset: 1705310060
```

When exceeded: `HTTP 429` with `Retry-After: {seconds}`.

---

## Multi-Tenancy Enforcement

**Every authenticated request is scoped to a single merchant.** The merchant context is extracted from:
- JWT: `merchantId` claim
- API key: `api_keys.merchant_id` DB column

All data access is filtered by this `merchant_id`. Controllers inject `@AuthenticatedMerchant` and all repository queries append `WHERE merchant_id = :merchantId`. A merchant can never see another merchant's data even with a valid token.

**Admin and Operator** roles bypass merchant filtering for cross-merchant operations (e.g., platform-wide reconciliation, risk review, DLQ inspection).

---

## Security Hardening

- API keys are stored as SHA-256 hash only
- JWT secrets are loaded from environment variables (never in `application.properties` in prod)
- Customer PII (email, phone) is AES-256-GCM encrypted at rest in the `customers` table
- All auth failures are logged with IP, timestamp, and masked key prefix (never the full key)
- No passwords are stored for merchant users in this system (SSO / OAuth2 integration point)
