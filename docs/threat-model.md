# Threat Model — FirstClub Membership Platform

> **Date:** 2026-03-06  
> **Version:** 1.0  
> **Author:** Shwet Raj  
> **Scope:** Public-facing API, payment flows, user data, admin endpoints

---

## 1. System Overview

The membership platform exposes a REST API that handles:
- User registration and authentication (JWT)
- Membership subscription management
- Payment intent creation and webhook ingestion
- Invoice generation and ledger accounting
- Outbox-based domain event delivery

```
Browser / Mobile App
       │  HTTPS
       ▼
  ┌─────────────┐    ┌──────────────────────────┐
  │  Spring Boot │────│  PostgreSQL (prod)         │
  │  REST API    │    │  H2 (dev)                  │
  └─────────────┘    └──────────────────────────┘
       │
       │ HMAC-signed webhook
       ▼
  ┌─────────────┐
  │  Payment     │
  │  Gateway     │
  └─────────────┘
```

---

## 2. Assets

| Asset | Classification | Owner |
|---|---|---|
| User PII (phone, address) | Restricted | Data team |
| Payment card tokens / gateway refs | Restricted | Payments team |
| JWT signing key (`app.jwt.secret`) | Secret | Ops |
| PII encryption key (`PII_ENC_KEY`) | Secret | Ops |
| Webhook HMAC secret (`payments.webhook.secret`) | Secret | Ops |
| Subscription + billing data | Confidential | Business |
| Audit logs | Confidential | Compliance |

---

## 3. Trust Boundaries

1. **Internet → API** — untrusted actors; enforced by Spring Security + JWT
2. **Admin endpoints** — authenticated + `ROLE_ADMIN` required
3. **Gateway → Webhook** — signed with HMAC-SHA256
4. **DB → JPA** — internal; encrypted at the column level for PII fields
5. **Outbox poller** — internal scheduled job; no external surface

---

## 4. Threat Catalogue (STRIDE)

### 4.1 Spoofing

| # | Threat | Mitigation |
|---|---|---|
| S1 | Attacker forges JWT to impersonate user | JWT validated on every request; 1-day expiry; refresh tokens are separate |
| S2 | Attacker replays webhook with old payload | Webhook idempotency — `event_id` checked against `webhook_events` before processing |
| S3 | Attacker forges webhook signature | HMAC-SHA256 with server-side secret; invalid-signature requests logged and rejected (`INVALID_SIGNATURE`) |

### 4.2 Tampering

| # | Threat | Mitigation |
|---|---|---|
| T1 | Attacker modifies stored PII in DB | AES-256-GCM provides authentication tag — tampered ciphertext fails decryption (`IllegalStateException`) |
| T2 | Attacker modifies invoice amount in transit | HTTPS TLS in transit; server-side amount validation |
| T3 | Attacker tampers with ledger entries | Ledger entries are immutable after creation; double-entry invariant checked before each write |

### 4.3 Repudiation

| # | Threat | Mitigation |
|---|---|---|
| R1 | User denies making a payment | `audit_logs` table records every action with `userId`, `requestId`, and timestamp |
| R2 | Admin denies blocking an IP | IP block operations recorded in `risk_events` and audit trail |

### 4.4 Information Disclosure

| # | Threat | Mitigation |
|---|---|---|
| I1 | PII leaked via DB dump | `phone_number` and `address` stored as AES-256-GCM ciphertext; key not in DB |
| I2 | Stack traces exposed in API responses | `GlobalExceptionHandler` returns only `errorCode` + `message`; no stack trace in body |
| I3 | JWT secret in source code | Loaded from env/config at runtime; dev default must be rotated before production |
| I4 | H2 console exposed in production | Guarded by `@Value("${spring.h2.console.enabled:false}")`; disabled when env var is unset |
| I5 | Actuator endpoints expose metrics | `/actuator/**` (except `/health`) requires `ROLE_ADMIN` |
| I6 | Log injection via user-controlled data | Logback JSON encoder escapes all values; no raw string concatenation in log statements |

### 4.5 Denial of Service

| # | Threat | Mitigation |
|---|---|---|
| D1 | Brute-force payment attempts (credential stuffing) | Velocity check: max 5 payment attempts per user per hour (429 response + `risk_events` record) |
| D2 | IP-based flooding | IP block-list checked before payment processing (403 response) |
| D3 | Expensive DB queries under load | HikariCP connection pool with tuned timeouts; indexed query paths |
| D4 | Outbox storm (backlog of events) | Exponential back-off (5/15/30/60 min) + max 5 attempts per event; dead-letter after exhaustion |

### 4.6 Elevation of Privilege

| # | Threat | Mitigation |
|---|---|---|
| E1 | Regular user calls admin endpoints | `@PreAuthorize("hasRole('ADMIN')")` on all admin controllers |
| E2 | SQL injection via user input | Spring Data JPA / JPQL with parameterised queries; no raw string concatenation |
| E3 | JWT token with forged `ROLE_ADMIN` | Tokens are server-signed; server validates signature before trusting claims |

---

## 5. Controls Summary

### Authentication & Authorisation
- Stateless JWT (JJWT 0.12.5), HS512 algorithm
- BCrypt work factor 12 (OWASP 2025)
- Method-level security with `@EnableMethodSecurity`
- Admin endpoints locked to `ROLE_ADMIN`

### PII Protection
- `User.phoneNumber` and `User.address` encrypted at rest with AES-256-GCM
- Key provisioned via `PII_ENC_KEY` env var (Base64, 32 bytes)
- Fresh random 12-byte IV per encryption call — eliminates ciphertext patterns
- Auth tag (128 bit) detects any storage-level tampering

### Payment Integrity
- All webhook events are HMAC-SHA256 verified before processing
- `event_id` idempotency prevents duplicate payment posts
- Ledger entries validated for double-entry balance before commit
- `payment_success_total` / `payment_failed_total` counters for anomaly alerting

### Risk Controls
- Velocity limit: 5 payment attempts / user / hour → 429
- IP block-list: admin-managed → 403
- Every blocked or allowed attempt written to `risk_events` with `user_id`, `ip`, `device_id`
- `RiskViolationException` raised in its own `REQUIRES_NEW` transaction so the event
  persists even if the outer payment transaction rolls back

### Observability
- Structured JSON logs (logback + logstash-logback-encoder): `requestId`, `requestPath`,
  `httpStatus`, `latencyMs` in every log line
- Micrometer counters: `payment_success_total`, `payment_failed_total`,
  `webhook_processed_total`, `webhook_failed_total`, `outbox_failed_total`,
  `ledger_unbalanced_total`
- All counters exportable via `/actuator/prometheus`

---

## 6. Out-of-Scope / Future Work

| Item | Notes |
|---|---|
| TLS termination | Handled by load balancer / reverse proxy in production |
| Secret rotation | `PII_ENC_KEY` rotation requires a re-encryption migration job (not yet implemented) |
| Rate limiting at gateway level | Recommend nginx/API gateway rate limiting upstream |
| IP range blocks (CIDR) | Current block-list is exact-match IPv4/IPv6 only |
| Fraud ML scoring | Velocity + IP block is rule-based; ML scoring is a future enhancement |
| Penetration testing | Automated SAST/DAST scans recommended before production launch |

---

## 7. References

- [OWASP Top 10 (2021)](https://owasp.org/www-project-top-ten/)
- [STRIDE Threat Modelling](https://docs.microsoft.com/en-us/azure/security/develop/threat-modeling-tool-threats)
- [NIST SP 800-38D — AES-GCM](https://csrc.nist.gov/publications/detail/sp/800-38d/final)
- Internal: [docs/outbox.md](outbox.md)
