# FirstClub Membership Program — Architecture Overview

> **Version:** 1.0.0 | **Author:** Shwet Raj | **Updated:** March 2026

---

## 1. Current System — One-Page Overview

### What It Is

A **production-ready membership subscription platform** built on Spring Boot 3.4.3 / Java 17,
targeting the Indian e-commerce market. It manages the full lifecycle of tiered memberships
(Silver / Gold / Platinum) including plan discovery, subscription creation, pro-rated upgrade /
downgrade, scheduled renewals and expiration, and admin analytics.

### Runtime Architecture

```
┌──────────────────────────────────────────────────────────┐
│                 HTTP Clients / Frontend                   │
└────────────────────────┬─────────────────────────────────┘
                         │ HTTPS
┌────────────────────────▼─────────────────────────────────┐
│           Spring Security Filter Chain                    │
│  RequestIdFilter → JwtAuthenticationFilter → ...         │
│  (X-Request-Id correlation, JWT blacklist check)          │
└──────────────┬───────────────────────────────────────────┘
               │ @PreAuthorize (SecurityService)
┌──────────────▼───────────────────────────────────────────┐
│                   REST Controllers (5)                    │
│  AuthController · UserController · PlanController        │
│  SubscriptionController · MembershipController           │
└──────────────┬───────────────────────────────────────────┘
               │ interfaces (DI)
┌──────────────▼───────────────────────────────────────────┐
│                   Service Layer (8 interfaces)            │
│  MembershipServiceImpl (~880 lines, core business logic) │
│  UserServiceImpl · PlanServiceImpl · TierServiceImpl     │
│  TokenBlacklistServiceImpl · LoginRateLimiterService     │
│  SecurityAuditContext · SchedulerService                 │
└──────────────┬───────────────────────────────────────────┘
               │ Spring Data JPA
┌──────────────▼───────────────────────────────────────────┐
│                 Repository Layer (5 repos)                │
│  UserRepository · SubscriptionRepository · PlanRepo      │
│  TierRepository · SubscriptionHistoryRepository          │
└────────────────────┬─────────────────────────────────────┘
                     │
           ┌─────────┴──────────┐
           │                    │
      H2 (dev)         PostgreSQL (local / prod)
```

### Technology Snapshot

| Concern | Solution |
|---|---|
| Framework | Spring Boot 3.4.3, Java 17, Maven |
| Auth | JWT (JJWT 0.12.5) — HS256, 24h access / 7d refresh |
| Password | BCrypt work-factor 12 |
| Mapping | MapStruct 1.5.5 (compile-time, zero reflection) |
| Caching | Caffeine — 6 named caches, 10-min TTL |
| Metrics | Micrometer → Prometheus (`/actuator/prometheus`) |
| API Docs | SpringDoc OpenAPI 2.8.3 (`/swagger-ui.html`) |
| DB Migrations | Flyway (V1–V3 initial schema + history + version) |
| Correlation | `X-Request-Id` header, MDC-injected per request |
| Scheduling | `@Scheduled` cron — nightly expiry (01:00) + renewal (01:05) |

### Key Design Decisions

- **Stateless JWT** — no server-side sessions; token blacklist (ConcurrentHashMap today → Redis in Phase 2)  
- **Soft delete** on `users` — referential integrity preserved; all queries filter `isDeleted=false`  
- **Optimistic locking** (`@Version`) on `subscriptions` — concurrent upgrades fail-fast with 409  
- **Pro-rated billing** — upgrade charge = `newPlanPrice − (remainingDays/totalDays × paidAmount)`  
- **Bulk scheduler** — single `UPDATE` SQL for expiry; paginated `REQUIRES_NEW` for renewals  
- **Caffeine cache** — plans and tiers are read-heavy; analytics cached for 10 min  

### Data Model (6 tables)

```
users ──────── user_roles
  │
  └── subscriptions ─── membership_plans ─── membership_tiers
            │
            └── subscription_history
```

### API Surface (35 endpoints across 5 controllers)

```
/api/v1/auth/**            — 4 endpoints  (login, register, refresh, logout)
/api/v1/users/**           — 11 endpoints (CRUD + subscription sub-resources)
/api/v1/plans/**           — 7 endpoints  (public plan discovery)
/api/v1/subscriptions/**   — 11 endpoints (subscription lifecycle)
/api/v1/membership/**      — 10 endpoints (admin catalogue + analytics)
```

### Deployment Profiles

| Profile | Database | DDL | Flyway | Seed Data |
|---|---|---|---|---|
| `dev` (default) | H2 in-memory | `create-drop` | off | DevDataSeeder |
| `local` | Docker Compose Postgres | `validate` | on | manual via API |
| `prod` | PostgreSQL (env vars) | `validate` | on | none |

---

## 2. Upcoming Fintech Modules

The following modules are planned for the next development phases.
Each module will be introduced as a new Spring Boot slice (separate package, interface-first).

### Phase 2 — Payments & Billing

| Module | Description |
|---|---|
| `payment-gateway` | Razorpay / Stripe integration for real INR transactions |
| `invoice-service` | PDF invoice generation on subscription creation and renewal |
| `refund-engine` | Pro-rated refund processing on cancellation or downgrade |
| `billing-cycle` | Dunning management — retry failed auto-renewals N times before suspension |

### Phase 3 — Fraud & Risk

| Module | Description |
|---|---|
| `fraud-detection` | Rule-based (velocity checks, geo-mismatch) + ML scoring pipeline |
| `kyc-service` | Aadhaar / PAN card verification before Platinum tier |
| `aml-screening` | Transaction amount limits + flagging for AML compliance |
| `rate-limiter-redis` | Replace in-memory `LoginRateLimiterService` with Redis for multi-instance |

### Phase 4 — Communication

| Module | Description |
|---|---|
| `notification-service` | Email (SES / SendGrid) + SMS (Twilio) for subscription events |
| `webhook-dispatcher` | Partner event webhooks (subscription created / cancelled / renewed) |
| `push-service` | Firebase Cloud Messaging for mobile apps |

### Phase 5 — Analytics & Observability

| Module | Description |
|---|---|
| `event-streaming` | Kafka or AWS Kinesis for real-time subscription event streaming |
| `audit-trail` | Immutable WORM log for regulatory compliance (SEBI / RBI) |
| `reporting` | Scheduled PDF / Excel reports sent to finance team |
| `dashboard-api` | Pre-aggregated endpoints for Grafana / internal BI tooling |

### Phase 6 — Platform Hardening

| Module | Description |
|---|---|
| `distributed-cache` | Migrate Caffeine caches to Redis; add distributed token blacklist |
| `circuit-breaker` | Resilience4j around payment gateway and notification calls |
| `multi-tenancy` | Namespace isolation per merchant / white-label partner |
| `feature-flags` | LaunchDarkly / Unleash for safe rollouts of new pricing models |

---

*This document is a living reference — update it as modules graduate from planned to in-progress to shipped.*
