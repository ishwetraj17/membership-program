# System Overview

## What Is This Platform?

FirstClub is a **multi-tenant subscription billing and membership management platform** built for the Indian market.  
It manages the full lifecycle from a customer subscribing to a plan → invoice generation → payment → revenue recognition → reconciliation → financial reporting.  
The system is a **production-shaped modular monolith** written in Java 17 / Spring Boot 3.4.3, backed by PostgreSQL.

---

## Who Uses It?

| Actor | Role |
|---|---|
| **Merchant** | Business entity that creates plans, manages customers, and receives subscription revenue |
| **Customer** | End user who takes a subscription and makes payments |
| **Admin / Operator** | Internal team member who reviews risk, repairs mismatches, runs reconciliation |
| **External Gateway** | Simulated payment processor that raises webhook callbacks on payment events |

---

## Core Domain Entities

| Entity | Description |
|---|---|
| `Merchant` | Tenant root. All data is scoped to a `merchant_id`. |
| `Customer` | Consumer profile. PII (phone, address) encrypted at rest (AES-256-GCM). |
| `MembershipPlan` / `MembershipTier` | Plan catalog: Silver/Gold/Platinum tiers at monthly/quarterly/yearly pricing |
| `ProductCatalog` / `Price` | V2 product catalog with flexible pricing models |
| `Subscription` / `SubscriptionV2` | Tracks plan enrollment, billing cycle, status, version |
| `Invoice` / `InvoiceLine` | Tax-compliant invoice with line-item breakdown (charge, discount, proration, tax) |
| `PaymentIntent` | Represents a single attempt to collect money; manages gateway coordination |
| `PaymentAttempt` | One call to a payment gateway; attempts are monotonically numbered |
| `Refund` / `RefundV2` | Partial or full reversal of a captured payment |
| `Dispute` | Chargeback raised by the customer's bank |
| `LedgerEntry` / `LedgerLine` | Immutable double-entry accounting record |
| `RevenueRecognitionSchedule` | Daily amortization schedule for deferred revenue (ASC 606 / IFRS 15) |
| `ReconMismatch` | Detected discrepancy between expected and actual financial state |
| `DomainEventOutbox` | Transactional outbox for reliable event delivery |

---

## Source-of-Truth Principles

1. **PostgreSQL is the only source of truth for all financial and business state.**
2. Redis (when added) is an acceleration layer only — for idempotency fast-paths, rate limiting, dedup markers, and caches. Financial correctness must hold with Redis down.
3. The ledger is append-only. No update or delete ever runs on `ledger_entries` or `ledger_lines`.
4. Domain events are written in the **same transaction** as the triggering operation — no dual-write risk.
5. All money amounts are stored as `NUMERIC(19,4)` in PostgreSQL. No floating point.

---

## Architecture Style

**Modular Monolith.**  
All 16 modules reside in a single deployable JAR. Each module owns its entities, repositories, services, and controllers. Inter-module calls go through service interfaces, not REST calls.

```
com.firstclub.membership/
├── membership/       (V1 tier plans)
├── merchant/         (multi-tenant root)
├── customer/         (PII, encrypted fields)
├── catalog/          (products, prices)
├── subscription/     (V2 lifecycle)
├── billing/          (invoicing, discounts, credit notes)
├── tax/              (India GST: CGST/SGST/IGST)
├── payment/          (V2 intent orchestration + routing)
├── ledger/           (double-entry accounting)
│   └── revenue/      (revenue recognition sub-module)
├── refund/           (V2 refunds + disputes)
├── dunning/          (V1 + V2 retry engine)
├── outbox/           (transactional outbox)
├── events/           (domain event log)
├── notification/     (webhooks, delivery)
├── risk/             (velocity limits, IP block)
├── recon/            (basic + advanced 4-layer reconciliation)
├── reporting/        (projections, ledger snapshots)
└── platform/         (ops, idempotency, feature flags, job locks)
```

---

## Module Dependency Map

```
                     ┌──────────────┐
                     │   merchant   │  ← tenant root
                     └──────┬───────┘
                            │ owns
              ┌─────────────┼──────────────┐
              ▼             ▼              ▼
          customer       catalog       membership
              │             │              │
              └──────┬──────┘              │
                     ▼                     ▼
                subscription  ←────── dunning
                     │
              ┌──────┴──────┐
              ▼             ▼
           billing         tax
              │
              ▼
           payment  ←── routing  ←── risk
              │
        ┌─────┴──────┐
        ▼             ▼
      refund        ledger
        │             │
        │             ▼
        │         revenue
        │         recognition
        └───────────────────────── recon
                                    │
                              reporting/
                              projections

outbox ──► events ──► notification/webhooks
  ▲
  └── (all modules write to outbox)

platform (cross-cutting: idempotency, feature flags, job locks, ops)
```

---

## Strong Consistency vs. Eventual Consistency

### Strongly Consistent (PostgreSQL ACID)

| Operation | Guarantee |
|---|---|
| Invoice creation and total calculation | Same transaction, all-or-nothing |
| Payment capture + ledger entry | Same transaction |
| Refund creation + ledger entry | Same transaction |
| Revenue recognition schedule creation | Same transaction as invoice |
| Outbox event write | Same transaction as the triggering operation |
| Subscription state change | Optimistic lock on `version` column |
| Idempotency key persistence | DB UNIQUE constraint as final arbiter |

### Eventually Consistent

| Operation | Lag Source |
|---|---|
| Outbox → domain event publication | Outbox poller delay (typically <5s) |
| Domain event → projection update | Event listener is async |
| Revenue recognition posting | Nightly scheduler run |
| Ledger balance snapshots | Nightly after reconciliation |
| Reconciliation mismatches | Nightly batch or on-demand |
| Dunning retry scheduling | Nightly scheduler |
| Webhook delivery to merchant endpoints | Async delivery with retry queue |

---

## Current Technology Stack

| Layer | Technology |
|---|---|
| Language | Java 17 (LTS) |
| Framework | Spring Boot 3.4.3 |
| Build | Maven |
| ORM | Spring Data JPA / Hibernate |
| DB (prod) | PostgreSQL 15+ |
| DB (test/CI) | H2 in-memory |
| Migrations | Flyway (V1–V34) |
| API docs | SpringDoc OpenAPI 3 / Swagger UI |
| Security | Spring Security (JWT + role-based) |
| Encryption | AES-256-GCM via JPA `@Converter` |
| Observability | Micrometer + structured MDC JSON logging |
| Tests | JUnit 5, Mockito, Spring Boot Test — 896+ tests, 0 failures |
| Cache / Acceleration | Redis 7 (Lettuce) — infrastructure ready, disabled by default (`app.redis.enabled=false`) |

---

## What This Is Not (Honest Scope)

- **Not a distributed system.** Single JVM process. No Kafka. No message broker. Outbox is polled, not pushed.
- **Not a high-frequency trading platform.** Handles hundreds of subscriptions per minute, not thousands of payments per second.
- **Not multi-region.** Single PostgreSQL instance. Horizontal scaling requires adding a read replica and session-affinity.
- **Not PCI-DSS Level 1 certified.** Cards are not stored. Gateway integration is currently simulated. Real gateway integration requires tokenization.
- **Redis infrastructure is deployed but disabled by default.** The `platform.redis` package provides full connection factory, key factory, JSON codec, availability service, and health indicator. Activate via `app.redis.enabled=true`. See `docs/performance/02-redis-usage.md`.
