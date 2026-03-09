# PROJECT_ANALYSIS.md

> **Full codebase analysis - generated 2026**
> Workspace: `com.firstclub` (Spring Boot 3.4.3 / Java 17 / PostgreSQL 16)
> Build baseline: **1374 tests · 0 failures · BUILD SUCCESS**

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Architecture Overview](#2-architecture-overview)
3. [Technology Stack](#3-technology-stack)
4. [Package & Module Structure](#4-package--module-structure)
5. [Database Catalogue — V1-V49](#5-database-catalogue)
6. [Module Analyses](#6-module-analyses)
   - 6.1 Membership (Auth/JWT, V1 Subscriptions)
   - 6.2 Merchant (Multi-Tenancy, API Keys, Mode)
   - 6.3 Customer (Billable Identity, PII Encryption)
   - 6.4 Catalog (Products, Versioned Pricing)
   - 6.5 Subscription V2 (State Machine)
   - 6.6 Billing (Invoices, Discounts, Proration, Credit Notes)
   - 6.7 Tax (India GST, Profiles)
   - 6.8 Payments V2 (Core Intents, Methods, Mandates)
   - 6.9 Payment Routing (Gateway Selection, Health Monitoring)
   - 6.10 Refunds V2 (Capacity Checks, Accounting)
   - 6.11 Disputes (Chargebacks, Evidence, Accounting)
   - 6.12 Ledger (Double-Entry, COA, Balance)
   - 6.13 Revenue Recognition (ASC 606, Waterfall)
   - 6.14 Dunning (V1 Scheduler + V2 Policy Engine)
   - 6.15 Outbox (Transactional Messaging, DLQ)
   - 6.16 Events (Domain Event Log, Replay)
   - 6.17 Notifications / Webhooks (Signed Delivery, Auto-Disable)
   - 6.18 Risk (V1 Basic + V2 Rule Engine, Manual Review)
   - 6.19 Reconciliation (4-Layer Advanced Recon)
   - 6.20 Reporting (Projections, KPI, Ops Dashboard, Timeline)
   - 6.21 Platform (Idempotency, Rate Limiting, Redis, Integrity, Repair, Dedup, Concurrency, OPS)
   - 6.22 Admin / Search (Unified Cross-Entity Search)
   - 6.23 Support (Cases, Notes, Polymorphic Linking)
7. [Test Suite Analysis](#7-test-suite-analysis)
8. [API Reference Summary](#8-api-reference-summary)
9. [Security Analysis](#9-security-analysis)
10. [Feature Matrix](#10-feature-matrix)
11. [Gap Analysis & Roadmap](#11-gap-analysis--roadmap)
12. [Fintech Benchmark](#12-fintech-benchmark)
13. [Operational Runbook](#13-operational-runbook)
14. [Compliance Posture](#14-compliance-posture)

---

## 1. Executive Summary

### 1.1 Project Overview

`com.firstclub` is a **production-grade, cloud-native, multi-tenant fintech SaaS platform** built on Spring Boot 3.4.3 / Java 17. It provides a full-stack subscription billing and payment orchestration engine covering the entire revenue lifecycle: plan catalog → subscription management → invoice generation → payment processing → ledger accounting → revenue recognition → dunning retry → reconciliation → risk control → compliance reporting.

The platform is deliberately modular — 18 top-level vertical domains communicate through Spring application events and an outbox-backed transactional messaging layer rather than direct inter-module calls. This keeps coupling low and enables independent deployment evolution for each domain.

### 1.2 Key Metrics

| Metric | Value |
|---|---|
| Java source files | 901 |
| Top-level domain modules | 18 |
| Flyway migrations | 49 (V1–V49) |
| Database tables | 90+ |
| REST endpoints | 200+ |
| Test count | **1374** |
| Test failures | **0** |
| Build status | **BUILD SUCCESS** |
| Java version | 17 |
| Spring Boot version | 3.4.3 |
| Database | PostgreSQL 16 (Testcontainers in tests) |
| Auth | JWT (access 15min) + Refresh Token (7d) |
| Encryption | AES-256-GCM (PII column-level) |
| Signing | HMAC-SHA256 (webhooks) |
| Caching / Rate limiting | Redis 7 (graceful degradation) |

### 1.3 Phase History (V1–V49)

| Phase | Migrations | What was built |
|---|---|---|
| Phase 1 — Bootstrap | V1–V5 | Core membership: users, tiers, plans, subscriptions V1, payments V1, refresh tokens, token blacklist, credit notes |
| Phase 2 — Platform Foundation | V6–V10 | Idempotency keys V1, invoices + invoice lines, double-entry ledger + COA seeding, security hardening (password change, failed logins), webhooks V1, feature flags, job locks, domain events, outbox |
| Phase 3 — Risk & Recon | V11–V14 | Risk events + IP blocklist, reconciliation mismatches, audit log, subscription history |
| Phase 4 — Multi-Tenancy | V15–V16 | Merchant accounts/users/settings, customer domain with AES-256-GCM PII |
| Phase 5 — Catalog & Billing V2 | V17–V18 | Products + versioned prices, subscriptions V2 with state machine + schedules |
| Phase 6 — Payments V2 | V19–V21 | Payment methods + mandates, payment intents V2 + attempts, gateway routing + health |
| Phase 7 — Billing Completeness | V22–V23 | Invoice sequences + discounts + redemptions, India GST tax profiles |
| Phase 8 — Accounting | V24–V26 | Revenue recognition schedules, refunds V2, disputes + dispute evidence, settlement batches |
| Phase 9 — Dunning V2 | V27–V29 | Policy-driven dunning + subscription payment preferences, signed merchant webhooks, domain event enhancements (correlation/causation IDs) |
| Phase 10 — Reporting | V30–V31 | Customer billing summary, merchant daily KPI, ledger balance snapshots, advanced reconciliation + external statement imports |
| Phase 11 — Risk V2 | V32–V34 | Risk rules engine + manual review, merchant API keys + mode switching, merchant settings expansion |
| Phase 12 — Hardening | V35–V41 | Idempotency V3 (merchant-scoped + endpoint_signature), rate limit events, routing snapshot on attempts, business effect fingerprints, integrity engine, repair audit, concurrency hardening |
| Phase 13 — Ops | V42–V49 | Ops summary projections, timeline events, search indexes, revenue waterfall, refund/dispute accounting flags, outbox/webhook delivery hardening, support cases + notes |

---

## 2. Architecture Overview

### 2.1 Architectural Style

The platform is a **single-process modular monolith** (not microservices). All 18 domain modules share one JVM, one database connection pool, and one Flyway migration set. This simplifies deployment and transactionality while keeping module boundaries enforced exclusively through Java package visibility and the Spring bean DI graph (no module is injected into another without a deliberate interface).

### 2.2 Architectural Patterns Catalog

| Pattern | Used By | Implementation |
|---|---|---|
| Repository per Aggregate | All modules | Spring Data JPA `JpaRepository<Entity, ID>` |
| DTO layer | All controllers | Separate request/response DTOs, never expose JPA entities directly |
| Service-Interface → Impl | All services | `XxxService` interface + `XxxServiceImpl` bean |
| Domain Event Log | `events` module | `DomainEvent` entity, `DomainEventLog` service, publishable via outbox |
| Transactional Outbox | `outbox` module | `OutboxEvent` table, scheduler with SKIP LOCKED polls, exactly-once DLQ |
| Double-Entry Ledger | `ledger` module | `LedgerEntry` + `LedgerLines`, enforced DR==CR per entry in Java before persist |
| State Machine | `subscription` module | `SubscriptionStateMachine` explicit allowed-transition map, guards illegal transitions |
| Revenue Recognition | `ledger/revenue` module | ASC 606 schedule-per-invoice, straight-line amortization, catch-up runs |
| Payment Routing | `payments/routing` module | Scoped (merchant → platform) rules, Redis cache, health-aware gateway selection |
| Idempotency Filter | `platform` module | Redis NX lock + DB persistence; `endpoint_signature` conflict detection |
| Sliding-Window Rate Limiter | `platform` module | Redis EVAL Lua script on sorted sets, ZREMRANGEBYSCORE + ZADD + PEXPIRE |
| Optimistic Locking | `subscription`, `ledger/revenue` | `@Version` on entities, retry on `OptimisticLockException` |
| PII Column Encryption | `customer` module | JPA `@Convert` with `EncryptedStringConverter` (AES-256-GCM, GCM nonce-per-write) |
| Webhook Signing | `notifications` module | `X-FirstClub-Signature: sha256=HMAC(secret, body)` — HMAC-SHA256 |
| Webhook Auto-Disable | `notifications` module | `consecutive_failures` counter; threshold → `auto_disabled_at` set |
| Business Effect Fingerprint | `platform/dedup` module | SHA-256 over business key fields, two-tier Redis+DB dedup |
| Integrity Invariant Engine | `platform/integrity` | 22 `InvariantChecker` beans, runnable on demand or scheduled |
| Repair Action Framework | `platform/repair` | 7 `RepairAction` beans, dry-run + audit trail |
| Risk Rule Engine | `risk` module | V2: configurable `RiskRule` entities, 4 `RuleEvaluator` strategies, BLOCK>REVIEW>CHALLENGE>ALLOW pipeline |
| 4-Layer Reconciliation | `recon` module | payments ↔ ledger ↔ settlement batches ↔ external statements |
| Pre-computed Projections | `reporting` module | Event-driven updates + nightly rebuilds; no ad-hoc query layer |
| SKIP LOCKED Processing | `outbox`, `dunning`, `webhook` | `SELECT ... FOR UPDATE SKIP LOCKED` prevents double-processing under concurrent schedulers |

### 2.3 High-Level Module Dependency Graph

```
                ┌─────────────────────────────────────────────────────────────┐
                │                    PLATFORM LAYER                           │
                │  Idempotency · RateLimit · Redis · Integrity · Repair       │
                │  Dedup · Concurrency · FeatureFlags · JobLocks · HealthCheck │
                └───────────────────────┬─────────────────────────────────────┘
                                        │ (all modules depend on platform)
     ┌──────────┐   ┌──────────┐   ┌───┴──────┐   ┌──────────┐   ┌──────────┐
     │membership│   │ merchant │   │ customer │   │ catalog  │   │  admin   │
     │  (auth)  │   │(tenancy) │   │  (PII)   │   │ (pricing)│   │ (search) │
     └──────────┘   └──────────┘   └──────────┘   └──────────┘   └──────────┘
                                        │
              ┌─────────────────────────┼─────────────────────────┐
              │                         │                         │
        ┌──────────┐               ┌────────┐               ┌──────────┐
        │subscript.│               │billing │               │   tax    │
        │  V2 SM   │               │ invoic.│               │ (GST)    │
        └──────────┘               └────────┘               └──────────┘
              │                         │
        ┌──────────┐               ┌────────┐
        │ dunning  │               │payments│──────┬─────────┬──────────┐
        │(V1+V2)   │               │  V2    │      │ routing │ refunds  │ disputes
        └──────────┘               └────────┘      └─────────┴──────────┴──────────┘
              │                         │
        ┌──────────┐               ┌────────┐   ┌──────────────┐
        │  outbox  │               │ ledger │   │   revenue    │
        │  (DLQ)   │               │ (DR=CR)│   │  recognition │
        └──────────┘               └────────┘   └──────────────┘
              │                         │
        ┌──────────┐   ┌──────────┐   ┌────────┐   ┌──────────┐
        │  events  │   │  notif.  │   │  risk  │   │  recon   │
        │ (replay) │   │ webhooks │   │ engine │   │ 4-layer  │
        └──────────┘   └──────────┘   └────────┘   └──────────┘
                                        │
                                  ┌──────────┐   ┌──────────┐
                                  │ reporting│   │ support  │
                                  │ (project)│   │ (cases)  │
                                  └──────────┘   └──────────┘
```

### 2.4 Transaction Boundary Map

| Scenario | @Transactional scope |
|---|---|
| Invoice creation | Single TX: create invoice + lines + outbox event |
| Payment capture | Single TX: update payment intent + create ledger entry + update invoice |
| Revenue recognition posting | REQUIRES_NEW per schedule line (isolated from caller) |
| Risk event persistence | REQUIRES_NEW (survives outer rollback) |
| Outbox event processing | REQUIRES_NEW per event (ack or fail atomically) |
| Idempotency key creation | REQUIRES_NEW (write-through without parent TX context) |
| Repair action execution | REQUIRES_NEW with dry-run support |
| Integrity checks | READ_ONLY TX per checker |

---

## 3. Technology Stack

| Layer | Technology | Version | Notes |
|---|---|---|---|
| Language | Java | 17 | LTS; records, sealed classes, text blocks in use |
| Framework | Spring Boot | 3.4.3 | Spring MVC + Spring Data JPA + Spring Security |
| Database | PostgreSQL | 16 | JSONB, SKIP LOCKED, partial indexes, text search |
| ORM | Hibernate / Spring Data JPA | 6.x | Native queries for SKIP LOCKED paths |
| Migrations | Flyway | 10.x | 49 versioned migrations, checksum-validated |
| Cache / Rate Limit | Redis 7 | (via Lettuce) | Sliding window Lua, NX idempotency lock, routing cache |
| Auth | Spring Security + JJWT | 0.12.x | HS256 JWT; access=15min, refresh=7d |
| Testing | JUnit 5 + Mockito + Testcontainers | — | PostgreSQL container for integration tests |
| Docs | SpringDoc OpenAPI (Swagger UI) | 2.x | `/swagger-ui.html` |
| Metrics | Micrometer | — | `ledger_unbalanced_total`, custom counters |
| Build | Maven | 3.x | `pom.xml` at root |
| Containerization | Docker | — | `Dockerfile` at root; Redis service dependency |

---

## 4. Package & Module Structure

Root package: `com.firstclub`

```
com.firstclub
├── membership/                    Module 1 — Auth + V1 Subscriptions
│   ├── config/                    JwtConfig, SecurityConfig, SwaggerConfig, DatabaseConfig
│   ├── controller/                AuthController, UserController, SubscriptionController (V1),
│   │                              MembershipController, PlanController
│   ├── dto/                       AuthRequest/Response, UserDTO, SubscriptionDTO, MembershipPlanDTO
│   ├── entity/                    User, MembershipTier, MembershipPlan, Subscription (V1),
│   │                              RefreshToken, TokenBlacklist
│   ├── exception/                 GlobalExceptionHandler, MembershipException (ErrorCode enum)
│   ├── repository/                UserRepository, MembershipTierRepository, ...
│   └── service/                   JwtService, AuthService, UserService, impl/
│
├── merchant/                      Module 2 — Multi-Tenancy
│   ├── controller/                MerchantController, MerchantUserController,
│   │                              MerchantSettingsController, MerchantApiKeyController,
│   │                              MerchantModeController
│   ├── dto/                       MerchantDTO, MerchantUserDTO, MerchantSettingsDTO,
│   │                              MerchantApiKeyDTO, CreateApiKeyResponse
│   ├── entity/                    MerchantAccount, MerchantUser, MerchantSettings,
│   │                              MerchantApiKey, MerchantMode
│   ├── repository/                ...
│   └── service/                   MerchantService, MerchantUserService, MerchantSettingsService,
│                                  MerchantApiKeyService, MerchantModeService, impl/
│
├── customer/                      Module 3 — Billable Identity (PII Encryption)
│   ├── controller/                CustomerController, CustomerNoteController
│   ├── dto/                       CustomerDTO, CustomerNoteDTO, CreateCustomerRequest
│   ├── entity/                    Customer (phone/address encrypted), CustomerNote (immutable)
│   ├── encryption/                EncryptedStringConverter (AES-256-GCM @Convert)
│   ├── repository/                CustomerRepository, CustomerNoteRepository
│   └── service/                   CustomerService, CustomerNoteService, impl/
│
├── catalog/                       Module 4 — Product & Price Catalog
│   ├── controller/                ProductController, PriceController, PriceVersionController
│   ├── dto/                       ProductDTO, PriceDTO, PriceVersionDTO
│   ├── entity/                    Product, Price, PriceVersion
│   ├── repository/                ...
│   └── service/                   ProductService, PriceService, PriceVersionService, impl/
│
├── subscription/                  Module 5 — Subscription V2 State Machine
│   ├── controller/                SubscriptionV2Controller, SubscriptionScheduleController
│   ├── dto/                       SubscriptionV2DTO, SubscriptionScheduleDTO, CreateSubscriptionRequest
│   ├── entity/                    SubscriptionV2 (@Version OCC), SubscriptionSchedule
│   ├── repository/                SubscriptionV2Repository, SubscriptionScheduleRepository
│   ├── service/                   SubscriptionV2Service, SubscriptionScheduleService, impl/
│   └── statemachine/              SubscriptionStateMachine (explicit allowed-transition map)
│
├── billing/                       Module 6 — Invoicing, Discounts, Proration, Credit Notes
│   ├── controller/                InvoiceController, DiscountController, CreditNoteController
│   ├── dto/                       InvoiceDTO, InvoiceLineDTO, DiscountDTO, CreditNoteDTO
│   ├── entity/                    Invoice, InvoiceLine, Discount, DiscountRedemption,
│   │                              CreditNote, InvoiceSequence
│   ├── repository/                ...
│   ├── service/                   InvoiceService, InvoiceTotalService, ProrationCalculator,
│   │                              DiscountService, InvoiceNumberService, CreditNoteService, impl/
│   └── tax/                       TaxProfile, CustomerTaxProfile, TaxCalculationService,
│                                  TaxProfileService (India GST — CGST/SGST/IGST)
│
├── payments/                      Module 7 — Payment Intents, Methods, Attempts
│   ├── controller/                PaymentIntentV2Controller, PaymentMethodController,
│   │                              PaymentAttemptController, GatewayHealthController
│   ├── dto/                       PaymentIntentDTO, PaymentMethodDTO, CreatePaymentIntentRequest
│   ├── entity/                    PaymentIntentV2, PaymentAttempt, PaymentMethod, PaymentMethodMandate
│   ├── gateway/                   PaymentGateway (interface), SimulatedPaymentGateway (adapter)
│   ├── routing/                   GatewayRouteRule, GatewayHealth, PaymentRoutingService,
│   │                              GatewayHealthService, PaymentRoutingServiceImpl
│   ├── refund/                    RefundV2, RefundServiceV2, RefundAccountingService
│   ├── dispute/                   Dispute, DisputeEvidence, DisputeService,
│   │                              DisputeAccountingService, DisputeEvidenceService
│   ├── repository/                ...
│   └── service/                   PaymentIntentV2Service, PaymentAttemptService,
│                                  PaymentMethodService, impl/
│
├── ledger/                        Module 8 — Double-Entry Bookkeeping
│   ├── controller/                LedgerController, LedgerBalanceController
│   ├── dto/                       LedgerEntryDTO, LedgerLineDTO, BalanceDTO
│   ├── entity/                    LedgerAccount, LedgerEntry, LedgerLine
│   ├── repository/                ...
│   ├── revenue/                   RevenueRecognitionSchedule, RevenueWaterfallProjection,
│   │                              RevenueRecognitionPostingService, RevenueWaterfallProjectionService
│   └── service/                   LedgerService (validateLines DR==CR), LedgerBalanceService,
│                                  LedgerAccountService, impl/
│
├── dunning/                       Module 9 — Payment Retry Engine
│   ├── controller/                DunningPolicyController, DunningAttemptController
│   ├── dto/                       DunningPolicyDTO, DunningAttemptDTO
│   ├── entity/                    DunningPolicy, DunningAttempt, SubscriptionPaymentPreference
│   ├── repository/                ...
│   ├── scheduler/                 DunningSchedulerV1, DunningSchedulerV2, RenewalScheduler
│   └── service/                   DunningServiceV2, DunningPolicyService, RenewalService, impl/
│
├── outbox/                        Module 10 — Transactional Messaging
│   ├── controller/                OutboxAdminController, DlqController
│   ├── dto/                       OutboxEventDTO, DeadLetterDTO
│   ├── entity/                    OutboxEvent, DeadLetterMessage
│   ├── handler/                   OutboxEventHandler (interface), DedupAwareOutboxHandler,
│   │                              InvoiceCreatedHandler, PaymentSucceededHandler,
│   │                              RefundIssuedHandler, SubscriptionActivatedHandler
│   ├── repository/                ...
│   ├── scheduler/                 OutboxScheduler (polls SKIP LOCKED)
│   └── service/                   OutboxService (MAX_ATTEMPTS=5, BACKOFF=[5,15,30,60]min), impl/
│
├── events/                        Module 11 — Domain Event Log + Replay
│   ├── controller/                EventAdminController, ReplayController
│   ├── dto/                       DomainEventDTO, ReplayRequestDTO
│   ├── entity/                    DomainEvent (correlation_id, causation_id, aggregate_type, event_version)
│   ├── repository/                DomainEventRepository
│   └── service/                   DomainEventLog, ReplayService, impl/
│
├── notifications/                 Module 12 — Merchant Webhooks
│   ├── controller/                MerchantWebhookEndpointController, WebhookAdminController
│   ├── dto/                       WebhookEndpointDTO, WebhookDeliveryDTO
│   ├── entity/                    MerchantWebhookEndpoint, MerchantWebhookDelivery
│   ├── repository/                ...
│   ├── scheduler/                 WebhookDeliveryScheduler (SKIP LOCKED)
│   └── service/                   MerchantWebhookDeliveryService, HttpWebhookDispatcher, impl/
│
├── risk/                          Module 13 — Fraud & Risk Engine
│   ├── controller/                RiskController, ManualReviewController, RiskRuleController
│   ├── dto/                       RiskDecisionDTO, ManualReviewDTO, RiskRuleDTO
│   ├── entity/                    RiskEvent, RiskRule, IpBlocklist, RiskDecision, ManualReviewCase
│   ├── evaluator/                 RuleEvaluator (interface),
│   │                              BlocklistIpEvaluator, IpVelocityEvaluator,
│   │                              UserVelocityEvaluator, DeviceReuseEvaluator
│   ├── repository/                ...
│   └── service/                   RiskService (V1), RiskDecisionService (V2),
│                                  ManualReviewService, RiskRuleService, impl/
│
├── recon/                         Module 14 — Reconciliation
│   ├── controller/                ReconController, SettlementBatchController,
│   │                              StatementImportController
│   ├── dto/                       ReconMismatchDTO, SettlementBatchDTO, StatementImportDTO
│   ├── entity/                    ReconMismatch, SettlementBatch, SettlementBatchItem,
│   │                              ExternalStatementImport, ReconReport
│   ├── repository/                ...
│   ├── scheduler/                 NightlyReconScheduler
│   └── service/                   ReconciliationService, AdvancedReconciliationService,
│                                  SettlementBatchService, ExternalStatementService, impl/
│
├── reporting/                     Module 15 — Projections & KPI Dashboard
│   ├── controller/                ReportingController, OpsProjectionController, TimelineController
│   ├── dto/                       CustomerBillingSummaryDTO, MerchantKpiDTO, OpsProjectionDTO
│   ├── entity/                    CustomerBillingSummaryProjection, MerchantDailyKpiProjection,
│   │                              LedgerBalanceSnapshot, SubscriptionStatusProjection,
│   │                              InvoiceSummaryProjection, PaymentSummaryProjection,
│   │                              ReconDashboardProjection, TimelineEvent
│   ├── repository/                ...
│   ├── scheduler/                 ProjectionRebuildScheduler, LedgerSnapshotScheduler
│   └── service/                   ProjectionUpdateService, ProjectionRebuildService,
│                                  LedgerSnapshotService, OpsProjectionUpdateService,
│                                  TimelineService, SearchCacheService, impl/
│
├── platform/                      Module 16 — Cross-Cutting Infrastructure
│   ├── idempotency/               IdempotencyKeyEntity, IdempotencyFilter, IdempotencyService
│   ├── ratelimit/                 RedisSlidingWindowRateLimiter (Lua script), RateLimitService,
│   │                              RateLimitFilter, RateLimitEvent
│   ├── redis/                     RedisConfig, RedisHealthIndicator, RedisAvailabilityChecker
│   ├── integrity/                 InvariantChecker (interface), IntegrityCheckRegistry,
│   │                              IntegrityRunService, IntegrityCheckRun, IntegrityCheckFinding
│   │                              + 22 checker implementations across billing/events/ledger/payments/recon/revenue
│   ├── repair/                    RepairAction (interface), RepairActionRegistry,
│   │                              RepairAuditService, RepairActionsAudit
│   │                              + 7 repair implementations
│   ├── dedup/                     BusinessEffectFingerprint, BusinessFingerprintService
│   ├── concurrency/               ConcurrencyGuard, BusinessLockScope
│   ├── statemachine/              StateMachineValidator
│   └── ops/                       FeatureFlag, MerchantFeatureFlag, JobLock,
│                                  FeatureFlagService, JobLockService, DeepHealthController,
│                                  DlqOpsController, SystemHealthController
│
├── admin/                         Module 17 — Unified Search
│   ├── controller/                SearchController
│   ├── dto/                       SearchResultDTO, SearchRequest
│   └── service/                   SearchService, SearchCacheService, impl/
│
└── support/                       Module 18 — Support Cases
    ├── controller/                SupportCaseController, SupportNoteController
    ├── dto/                       SupportCaseDTO, SupportNoteDTO, CreateSupportCaseRequest
    ├── entity/                    SupportCase, SupportNote
    ├── repository/                SupportCaseRepository, SupportNoteRepository
    └── service/                   SupportCaseService, impl/
```


---

## 5. Database Catalogue — All 49 Migrations

### V1 — Bootstrap (users, tiers, plans, subscriptions V1, payments V1)

```sql
users                — id, email, password_hash, role(ADMIN/USER), created_at, updated_at
membership_tiers     — id, name, description, level(INT hierarchy), created_at
membership_plans     — id, name, tier_id(FK), billing_cycle, price, features_json, is_active
subscriptions        — id, user_id(FK), plan_id(FK), status(ACTIVE/CANCELLED/EXPIRED),
                        start_date, end_date, auto_renew, created_at, updated_at
payments             — id, user_id(FK), subscription_id(FK), amount, currency,
                        payment_gateway, gateway_transaction_id, status, created_at
```

### V2 — Refresh Tokens

```sql
refresh_tokens       — id, user_id(FK), token(UNIQUE), expires_at, revoked, created_at
```

### V3 — Token Blacklist

```sql
token_blacklist      — id, token(UNIQUE), blacklisted_at, expires_at, reason
```

### V4 — Billing Extensions on Payments

```sql
ALTER payments       ADD: invoice_id, payment_method, failure_reason, processed_at
```

### V5 — Credit Notes

```sql
credit_notes         — id, user_id(FK), subscription_id(FK), amount, currency, reason,
                        status(PENDING/APPLIED/EXPIRED), created_at
```

### V6 — Idempotency Keys V1

```sql
idempotency_keys     — id, key(UNIQUE VARCHAR 80), request_hash, response_body,
                        status_code, merchant_id, created_at, expires_at
INDEX on (key, merchant_id)
```

### V7 — Invoices + Invoice Lines

```sql
invoices             — id, user_id(FK), subscription_id(FK), amount, currency,
                        due_date, paid_at, status, created_at
invoice_lines        — id, invoice_id(FK), description, amount, quantity, unit_price,
                        line_type(PLAN_CHARGE/PRORATION/DISCOUNT/TAX/CGST/SGST/IGST/
                                  CREDIT_APPLIED), created_at
```

### V8 — Ledger (Double-Entry + COA Seed)

```sql
ledger_accounts      — id, code(UNIQUE), name, type(ASSET/LIABILITY/INCOME/EXPENSE),
                        description, created_at
SEEDED:
  RECEIVABLE         — ASSET          (amounts owed by customers)
  CASH               — ASSET          (settled cash from gateway)
  DISPUTE_RESERVE    — ASSET          (funds held for chargeback coverage)
  REVENUE_SUBSCRIPTIONS — INCOME      (earned subscription revenue)
  SUBSCRIPTION_LIABILITY — LIABILITY  (deferred/unearned revenue)
  REFUND_EXPENSE     — EXPENSE        (cost of refunds)
  CHARGEBACK_EXPENSE — EXPENSE        (cost of chargebacks)
  SETTLEMENT         — ASSET          (net settlement from gateway)

ledger_entries       — id, entry_type, reference_type, reference_id, currency, memo, created_at
ledger_lines         — id, entry_id(FK), account_id(FK), side(DEBIT/CREDIT), amount, created_at
INVARIANT (Java): sum(DEBIT lines) == sum(CREDIT lines) per entry; violation throws LEDGER_UNBALANCED
```

### V9 — Security Hardening

```sql
ALTER users          ADD: password_changed_at, last_login_at, failed_login_count
ALTER subscriptions  ADD: version (optimistic lock counter)
```

### V10 — Platform Foundation

```sql
webhook_endpoints         — id, user_id(FK), url, secret, event_types_json, is_active
webhook_deliveries        — id, endpoint_id(FK), event_type, payload, status,
                             attempt_count, last_attempted_at, next_attempt_at
feature_flags             — id, flag_key(UNIQUE), is_enabled, description, created_at, updated_at
merchant_feature_flags    — id, merchant_id, flag_key, is_enabled;  UNIQUE(merchant_id, flag_key)
job_locks                 — id, job_name(UNIQUE), locked_by, locked_at, lock_expires_at, last_run_at
domain_events             — id, event_type, aggregate_id, payload_json, recorded_at
outbox_events             — id, event_type, payload_json, status(NEW/PROCESSING/PROCESSED/FAILED/DEAD),
                             created_at, processed_at, attempt_count, next_attempt_at, error_message
dead_letter_messages      — id, original_event_id, event_type, payload_json, failure_reason,
                             exhausted_at, created_at
```

### V11 — Risk V1

```sql
risk_events          — id, event_type(IP_BLOCKED/VELOCITY_EXCEEDED/PAYMENT_ATTEMPT/ACCOUNT_BLOCKED),
                        ip_address, user_id, merchant_id, device_id, metadata_json, severity, created_at
ip_blocklist         — ip(PK), reason, blocked_at, created_at
```

### V12 — Reconciliation V1

```sql
recon_mismatches     — id, recon_date, reference_type, reference_id, expected_amount,
                        actual_amount, difference, status(OPEN/RESOLVED/IGNORED),
                        created_at, resolved_at, resolution_note
```

### V13 — Audit Log

```sql
audit_log            — id, action, entity_type, entity_id, performed_by, metadata_json, created_at
```

### V14 — Subscription History

```sql
subscription_history — id, subscription_id(FK), from_status, to_status, changed_by, reason, created_at
```

### V15 — Merchant Accounts (Multi-Tenancy)

```sql
merchant_accounts    — id, name, legal_name, email, phone, status(ACTIVE/INACTIVE/SUSPENDED),
                        plan_type, created_at, updated_at
merchant_users       — id, merchant_id(FK), user_id(FK), role(READ_ONLY/OPERATOR/ADMIN);
                        UNIQUE(merchant_id, user_id)
merchant_settings    — id, merchant_id(FK UNIQUE), webhook_enabled,
                        settlement_frequency(DAILY/WEEKLY/MONTHLY), auto_retry_enabled,
                        default_grace_days, default_dunning_policy_code, created_at, updated_at
```

### V16 — Customer Domain (AES-256-GCM PII Encryption)

```sql
customers            — id, merchant_id(FK), external_customer_id, name, email,
                        phone(ENCRYPTED), billing_address(ENCRYPTED), shipping_address(ENCRYPTED),
                        status(ACTIVE/INACTIVE/BLOCKED), metadata_json, created_at, updated_at
                        UNIQUE(merchant_id, email)
                        UNIQUE(merchant_id, external_customer_id) WHERE NOT NULL

customer_notes       — id, customer_id(FK), content, visibility(INTERNAL_ONLY/MERCHANT_VISIBLE),
                        created_by_user_id, created_at   [immutable — no updated_at]
```

### V17 — Catalog: Products & Versioned Pricing

```sql
products             — id, merchant_id(FK), product_code, name, description,
                        status(ACTIVE/ARCHIVED), metadata_json, created_at, updated_at
                        UNIQUE(merchant_id, product_code)

prices               — id, product_id(FK), merchant_id(FK), name,
                        billing_type(RECURRING/ONE_TIME),
                        billing_interval_unit(DAY/WEEK/MONTH/YEAR), billing_interval_count,
                        trial_days, currency, is_active, metadata_json, created_at, updated_at

price_versions       — id, price_id(FK), unit_amount, currency, effective_from, effective_to,
                        grandfather_existing_subscriptions, created_by_user_id, created_at, updated_at
                        CONSTRAINT: no overlapping [effective_from, effective_to) per price_id
```

### V18 — Subscriptions V2 + Schedules

```sql
subscriptions_v2     — id, merchant_id(FK), customer_id(FK), product_id(FK), price_id(FK),
                        price_version_id(FK), status(INCOMPLETE/TRIALING/ACTIVE/PAUSED/
                                                     PAST_DUE/SUSPENDED/CANCELLED/EXPIRED),
                        current_period_start, current_period_end, trial_end,
                        cancel_at_period_end, billing_anchor_at, cancel_reason,
                        cancelled_at, version(OCC), metadata_json, created_at, updated_at

subscription_schedules — id, subscription_id(FK), scheduled_action(UPGRADE/DOWNGRADE/CANCEL/PAUSE/RESUME),
                          execute_at, status(PENDING/EXECUTED/CANCELLED), payload_json, created_at, updated_at
```

### V19 — Payment Methods & Mandates

```sql
payment_methods      — id, merchant_id(FK), customer_id(FK), type(CARD/UPI/NETBANKING/WALLET),
                        provider, provider_token(tokenized), fingerprint, last4, brand,
                        expiry_month, expiry_year, is_default, status(ACTIVE/EXPIRED/REVOKED)
                        UNIQUE(provider, provider_token)

payment_method_mandates — id, payment_method_id(FK), subscription_id(FK), mandate_reference,
                           mandate_type(ENACH/UPI_AUTOPAY), max_amount, currency,
                           status(ACTIVE/REVOKED/EXPIRED), approved_at, revoked_at, expires_at
```

### V20 — Payment Intents V2 + Attempts

```sql
payment_intents_v2   — id, merchant_id(FK), customer_id(FK), subscription_id(FK?),
                        invoice_id(FK?), payment_method_id(FK?), amount, currency,
                        status(REQUIRES_PAYMENT_METHOD/REQUIRES_CONFIRMATION/PROCESSING/
                               SUCCEEDED/CANCELLED/REQUIRES_ACTION),
                        capture_mode(AUTO/MANUAL), client_secret(UNIQUE), idempotency_key,
                        gateway, gateway_payment_id, captured_amount, refunded_amount,
                        disputed_amount, net_amount, metadata_json, created_at, updated_at

payment_attempts     — id, payment_intent_id(FK), attempt_number, gateway_name,
                        status(INITIATED/SUCCEEDED/FAILED/CANCELLED), gateway_response_code,
                        failure_reason, failure_category, is_fallback, is_retriable,
                        latency_ms, routing_snapshot_json(TEXT, V37 added), created_at
```

### V21 — Gateway Routing + Health

```sql
gateway_route_rules  — id, merchant_id(NULL=platform-default), method_type, currency,
                        country_code, priority, preferred_gateway, fallback_gateway,
                        retry_number(0=first, n=nth retry), is_active, created_at, updated_at

gateway_health       — id, gateway_name(UNIQUE), status(HEALTHY/DEGRADED/DOWN),
                        success_rate_pct, p95_latency_ms, last_checked_at, updated_at
SEEDED:
  razorpay  — 99.5%, 120ms, HEALTHY
  stripe    — 99.8%, 95ms, HEALTHY
  payu      — 98.2%, 180ms, HEALTHY
```

### V22 — Invoice Sequences + Discounts

```sql
invoice_sequences    — id, merchant_id(FK UNIQUE), prefix, current_number, created_at, updated_at

discounts            — id, merchant_id(FK), code, type(FIXED/PERCENTAGE),
                        amount_off, percent_off, max_redemptions, per_customer_limit,
                        valid_from, valid_to, is_active;  UNIQUE(merchant_id, code)

discount_redemptions — id, discount_id(FK), customer_id(FK), invoice_id(FK),
                        amount_applied, redeemed_at

ALTER invoices       ADD: merchant_id, invoice_number, subtotal, discount_total,
                          credit_total, tax_total, grand_total, total_amount
```

### V23 — Tax Profiles (India GST)

```sql
tax_profiles         — id, merchant_id(FK UNIQUE), gstin, legal_name, legal_state_code,
                        is_tax_registered, created_at, updated_at

customer_tax_profiles — id, customer_id(FK UNIQUE), gstin, is_tax_exempt, state_code,
                         entity_type(INDIVIDUAL/BUSINESS), created_at, updated_at
```

### V24 — Revenue Recognition Schedules

```sql
revenue_recognition_schedules — id, merchant_id(FK), subscription_id(FK), invoice_id(FK),
                                  ledger_entry_id(FK?), recognition_date, amount, currency,
                                  status(PENDING/POSTED), version(OCC, V41),
                                  generation_fingerprint(V45), posting_run_id(V45),
                                  catch_up_run(V45), created_at, updated_at
UNIQUE(invoice_id, recognition_date)   — V41, prevents duplicate generation
INDEX(recognition_date, status) WHERE status='PENDING'  — V41, SKIP LOCKED batch scan
```

### V25 — Refunds V2, Disputes, Settlement Batches

```sql
refunds_v2           — id, merchant_id(FK), payment_id(FK), invoice_id(FK?), amount, currency,
                        reason, status(PENDING/COMPLETED/FAILED), gateway_refund_id,
                        request_fingerprint(V46), created_at, updated_at

disputes             — id, merchant_id(FK), payment_id(FK), amount, currency, reason,
                        status(OPEN/UNDER_REVIEW/WON/LOST/CLOSED), due_by, resolved_at,
                        reserve_posted(BOOLEAN, V46), resolution_posted(BOOLEAN, V46),
                        created_at, updated_at

dispute_evidence     — id, dispute_id(FK), evidence_type, file_url, description,
                        submitted_at, created_at

settlement_batches   — id, merchant_id(FK), gateway, batch_date, gross_amount, fee_amount,
                        reserve_amount, net_amount, currency,
                        status(PENDING/SETTLED/FAILED), settled_at, created_at
                        UNIQUE(merchant_id, gateway, batch_date)

settlement_batch_items — id, batch_id(FK), payment_id(FK?), refund_id(FK?), amount,
                          item_type, created_at
```

### V26 — Dispute Evidence & Indexes

Additional dispute evidence types; composite indexes for dispute status queries.

### V27 — Dunning V2 (Policy-Driven Engine)

```sql
dunning_policies     — id, merchant_id(NULL=platform-default), code(UNIQUE), name,
                        retry_offsets_json (e.g. [1440,4320,10080] = 1d,3d,7d in minutes),
                        max_attempts, grace_days, fallback_to_backup_payment_method,
                        status_after_exhaustion(SUSPENDED/CANCELLED), is_active

subscription_payment_preferences — id, subscription_id(FK UNIQUE),
                                    primary_payment_method_id(FK),
                                    backup_payment_method_id(FK?),
                                    retry_order_json, created_at, updated_at

dunning_attempts     — id, merchant_id(FK), subscription_id(FK), invoice_id(FK),
                        attempt_number, scheduled_at, attempted_at,
                        status(SCHEDULED/SUCCESS/FAILED), payment_method_id(FK),
                        failure_reason, is_backup_method, dunning_policy_id(FK?), created_at
```

### V28 — Merchant Webhooks V2 (HMAC-SHA256 Signed)

```sql
merchant_webhook_endpoints  — id, merchant_id(FK), url, secret(hashed), subscribed_events_json,
                               is_active, description,
                               consecutive_failures(INT, V48=0 default),
                               auto_disabled_at(TIMESTAMP?, V48), created_at, updated_at

merchant_webhook_deliveries — id, endpoint_id(FK), event_type, payload_json,
                               status(PENDING/DELIVERED/FAILED/GAVE_UP), attempt_count,
                               response_code, response_body, next_attempt_at, delivered_at,
                               processing_owner(V48), processing_started_at(V48),
                               delivery_fingerprint(V48), created_at
```

### V29 — Domain Event & Outbox Enhancements

```sql
ALTER domain_events  ADD: event_version, schema_version, correlation_id, causation_id,
                           aggregate_type, aggregate_id, merchant_id
ALTER outbox_events  ADD: event_version, schema_version, correlation_id, causation_id,
                           aggregate_type, merchant_id
                           [V47 further adds: processing_started_at, processing_owner,
                                              handler_fingerprint, failure_category]
```

### V30 — Reporting Projections

```sql
customer_billing_summary_projections — PK(merchant_id, customer_id),
                                        active_subscriptions_count, unpaid_invoices_count,
                                        total_paid_amount, total_refunded_amount,
                                        last_payment_at, updated_at

merchant_daily_kpi_projections       — PK(merchant_id, kpi_date), new_subscriptions,
                                        cancelled_subscriptions, payment_success_count,
                                        payment_failure_count, gross_revenue, net_revenue,
                                        refund_count, refund_amount, updated_at

ledger_balance_snapshots             — id, account_id(FK), snapshot_date, merchant_id?,
                                        opening_balance, closing_balance, period_debits,
                                        period_credits, currency, created_at
                                        UNIQUE(account_id, snapshot_date, merchant_id)
```

### V31 — Advanced Reconciliation

```sql
ALTER recon_mismatches  ADD: layer(2/3/4), merchant_id, gateway, owner_user_id,
                              resolved_by, resolution_note

statement_imports    — id, merchant_id(FK), gateway, statement_date, file_name,
                        row_count, total_amount, currency,
                        status(PENDING/PROCESSING/COMPLETED/FAILED),
                        imported_by_user_id, imported_at, created_at
```

### V32 — Risk Rules V2 + Manual Review

```sql
risk_rules           — id, merchant_id(NULL=platform-wide), rule_type(IP_VELOCITY_LAST_10_MIN/
                        USER_VELOCITY_LAST_HOUR/DEVICE_REUSE/BLOCKLIST_IP), name,
                        rule_code(UNIQUE per merchant), config_json (thresholds + score),
                        action(ALLOW/CHALLENGE/REVIEW/BLOCK), priority, is_active

risk_decisions       — id, merchant_id(FK), payment_intent_id(FK), customer_id(FK),
                        score(INT), decision(ALLOW/CHALLENGE/REVIEW/BLOCK),
                        matched_rules_json, created_at

manual_review_cases  — id, merchant_id(FK), payment_intent_id(FK UNIQUE),
                        status(OPEN/APPROVED/REJECTED/ESCALATED),
                        assigned_to(user_id?), risk_score, triggered_rules_json,
                        created_at, updated_at
```

### V33 — Merchant API Keys + Mode Switching

```sql
merchant_api_keys    — id, merchant_id(FK), mode(SANDBOX/LIVE),
                        key_prefix(16 hex, UNIQUE — plaintext for O(1) lookup),
                        key_hash(SHA-256 — for verification), description,
                        is_active, last_used_at, created_at, updated_at

merchant_modes       — id, merchant_id(FK UNIQUE), current_mode(SANDBOX/LIVE),
                        live_enabled_at, created_at, updated_at
```

### V34 — Merchant Settings Expansion

```sql
ALTER merchant_settings ADD: max_payment_retry_count, notify_on_payment_failure,
                              notify_on_subscription_cancel, invoice_prefix_override,
                              tax_inclusive_pricing, timezone, locale
```

### V35 — Idempotency V3: Merchant-Scoped + Endpoint Signature

```sql
ALTER idempotency_keys
  key           widened to VARCHAR(255)
  merchant_id   VARCHAR(255) — part of composite dedup key
  endpoint_signature VARCHAR(255) — "{METHOD}:{url-template}" for conflict detection
  content_type  VARCHAR(128)

Internal composite key = "{merchantId}:{rawKey}"
Conflict detection: same key served from different endpoint_signature = 409 Conflict
INDEX on (merchant_id) for admin lookup; INDEX on (key) for primary dedup
```

### V36 — Rate Limit Events Audit

```sql
rate_limit_events    — id(UUID), category, subject_key, merchant_id?, blocked(BOOLEAN),
                        request_id, reason, created_at
INDEX on (blocked, created_at), (category)
```

Non-transactional best-effort audit. Used by ops dashboard for rate limit analysis.

### V37 — Payment Attempt Routing Snapshot

```sql
ALTER payment_attempts ADD routing_snapshot_json TEXT NULLABLE
```

Captures full routing decision JSON: matched_rule_id, attempted_gateway, fallback_gateway, preferred_gateway_health, fallback_gateway_health, reason, merchant_scope, rule_priority.

### V38 — Business Effect Fingerprints + Webhook Provider

```sql
business_effect_fingerprints — id(BIGSERIAL), effect_type(VARCHAR 64),
                                fingerprint(VARCHAR 128), reference_type, reference_id(BIGINT),
                                created_at
                                UNIQUE(effect_type, fingerprint)
INDEX on (effect_type), (created_at)

Fingerprint types: PAYMENT_CAPTURE_SUCCESS, REFUND_COMPLETED, DISPUTE_OPENED,
                   SETTLEMENT_BATCH_CREATED, REVENUE_RECOGNITION_POSTED

ALTER webhook_events ADD provider VARCHAR(32) DEFAULT 'gateway'
```

### V39 — Integrity Engine Persistence

```sql
integrity_check_runs     — id(BIGSERIAL), started_at, finished_at, initiated_by_user_id,
                            status(RUNNING/COMPLETED/PARTIAL_FAILURE/ERROR),
                            total_checks, failed_checks, summary_json,
                            merchant_id?, invariant_key? (NULL=all checks)
INDEX on (started_at DESC), (status), partial WHERE merchant_id IS NOT NULL

integrity_check_findings — id(BIGSERIAL), run_id(FK), invariant_key,
                            severity(CRITICAL/HIGH/MEDIUM/LOW),
                            status(PASS/FAIL/ERROR), violation_count,
                            details_json, suggested_repair_key, created_at
INDEX on (run_id), (invariant_key), (severity, status)
```

### V40 — Repair Actions Audit Trail

```sql
repair_actions_audit — id(BIGSERIAL), repair_key, target_type, target_id,
                        actor_user_id, before_snapshot_json, after_snapshot_json,
                        reason, status(EXECUTED/DRY_RUN_SKIPPED), dry_run(BOOLEAN),
                        created_at
INDEX on (repair_key), (target_type, target_id), (actor_user_id), (created_at DESC)
Immutable: no UPDATE ever issued on this table.
```

### V41 — Concurrency + SKIP LOCKED Hardening

```sql
ALTER revenue_recognition_schedules ADD version BIGINT NOT NULL DEFAULT 0
ADD UNIQUE(invoice_id, recognition_date)  — prevents duplicate generation under concurrency
ADD PARTIAL INDEX WHERE status='PENDING' on (recognition_date, status)  — SKIP LOCKED scan

ADD INDEX on dunning_attempts (scheduled_at, status)
    WHERE dunning_policy_id IS NOT NULL AND status='SCHEDULED'  — V2 scheduler SKIP LOCKED

ADD INDEX on merchant_webhook_deliveries (status, next_attempt_at)
    WHERE status IN ('PENDING','FAILED')  — webhook retry SKIP LOCKED
```

### V42 — Ops Summary Projections

```sql
subscription_status_projection  — PK(merchant_id, status), subscription_count, updated_at
invoice_summary_projection      — PK(merchant_id, status), invoice_count, total_amount, updated_at
payment_summary_projection      — PK(merchant_id, status), payment_count, total_amount, updated_at
recon_dashboard_projection      — PK(merchant_id), open_mismatches, resolved_mismatches,
                                   total_amount_off, last_recon_at, updated_at
```

### V43 — Ops Timeline Events

```sql
ops_timeline_events  — id(BIGSERIAL), merchant_id, entity_type(VARCHAR 64),
                        entity_id(BIGINT), event_type(VARCHAR 128),
                        event_data_json(TEXT), severity(VARCHAR 32), created_at
INDEX on (merchant_id, created_at DESC)
    (merchant_id, entity_type, entity_id)
    (merchant_id, severity, created_at DESC)
```

### V44 — Search Indexes

Adds GIN/B-tree composite indexes on customers, products, invoices, payment_intents_v2, subscriptions_v2, merchant_accounts for the unified search feature in `admin/search`. Uses PostgreSQL `to_tsvector` for full-text columns.

### V45 — Revenue Recognition + Waterfall Projection

```sql
ALTER revenue_recognition_schedules
  ADD generation_fingerprint VARCHAR(255)  — SHA-256 prevents duplicate schedule generation
  ADD posting_run_id BIGINT                — groups a recognition batch run
  ADD catch_up_run BOOLEAN DEFAULT FALSE   — flags retroactive catch-up runs

revenue_waterfall_projection — id(BIGSERIAL), merchant_id, business_date(DATE),
                                billed_amount, deferred_opening, deferred_closing,
                                recognized_amount, refunded_amount, disputed_amount, updated_at
                                UNIQUE(merchant_id, business_date)
INDEX on (merchant_id, business_date)
```

### V46 — Refund & Dispute Accounting Hardening

```sql
ALTER refunds_v2  ADD request_fingerprint VARCHAR(255)
    — SHA-256 over (merchant_id, payment_id, amount, reason) prevents duplicate refund requests

ALTER disputes    ADD reserve_posted BOOLEAN NOT NULL DEFAULT FALSE
                  ADD resolution_posted BOOLEAN NOT NULL DEFAULT FALSE
    — track whether DISPUTE_RESERVE and resolution ledger entries have been posted
```

### V47 — Outbox & DLQ Hardening

```sql
ALTER outbox_events ADD:
  processing_started_at TIMESTAMP  — per-instance lease start
  processing_owner VARCHAR(255)    — hostname/instance ID for lease tracking
  handler_fingerprint VARCHAR(255) — dedup for same-handler double-processing
  failure_category VARCHAR(64)     — TIMEOUT / HANDLER_ERROR / MAPPING_ERROR etc.

ALTER dead_letter_messages ADD:
  failure_category VARCHAR(64)
  merchant_id BIGINT
```

### V48 — Webhook Delivery Hardening

```sql
ALTER merchant_webhook_endpoints ADD:
  consecutive_failures INT NOT NULL DEFAULT 0
  auto_disabled_at TIMESTAMP  — set when consecutive_failures > threshold

ALTER merchant_webhook_deliveries ADD:
  processing_owner VARCHAR(255)      — hostname/instance for lease
  processing_started_at TIMESTAMP    — lease start
  delivery_fingerprint VARCHAR(255)  — prevents duplicate delivery
```

### V49 — Support Cases & Notes

```sql
support_cases  — id(BIGSERIAL), merchant_id, linked_entity_type(VARCHAR 64),
                  linked_entity_id(BIGINT)  [polymorphic FK to any entity],
                  title, status(OPEN/IN_PROGRESS/RESOLVED/CLOSED),
                  priority(CRITICAL/HIGH/MEDIUM/LOW), owner_user_id(NULLABLE),
                  created_at, updated_at
INDEX on (merchant_id), (linked_entity_type, linked_entity_id),
         (merchant_id, status), (owner_user_id)

support_notes  — id(BIGSERIAL), support_case_id(FK), content(TEXT NOT NULL),
                  visibility(INTERNAL_ONLY/MERCHANT_VISIBLE),
                  created_by_user_id, created_at  [immutable]
INDEX on (support_case_id)
```

---

### Complete Table Inventory (~90 tables, alphabetical)

| Table | Domain Module | Added |
|---|---|---|
| `audit_log` | membership | V13 |
| `business_effect_fingerprints` | platform/dedup | V38 |
| `credit_notes` | billing | V5 |
| `customer_billing_summary_projections` | reporting | V30 |
| `customer_notes` | customer | V16 |
| `customer_tax_profiles` | billing/tax | V23 |
| `customers` | customer | V16 |
| `dead_letter_messages` | outbox | V10 |
| `discount_redemptions` | billing | V22 |
| `discounts` | billing | V22 |
| `dispute_evidence` | payments/disputes | V25-V26 |
| `disputes` | payments/disputes | V25 |
| `domain_events` | events | V10 |
| `dunning_attempts` | dunning | V10+V27 |
| `dunning_policies` | dunning | V27 |
| `feature_flags` | platform/ops | V10 |
| `gateway_health` | payments/routing | V21 |
| `gateway_route_rules` | payments/routing | V21 |
| `idempotency_keys` | platform | V6+V35 |
| `integrity_check_findings` | platform/integrity | V39 |
| `integrity_check_runs` | platform/integrity | V39 |
| `invoice_lines` | billing | V7 |
| `invoice_sequences` | billing | V22 |
| `invoice_summary_projection` | reporting/ops | V42 |
| `invoices` | billing | V7+V22 |
| `ip_blocklist` | risk | V11 |
| `job_locks` | platform/ops | V10 |
| `ledger_accounts` | ledger | V8 |
| `ledger_balance_snapshots` | reporting | V30 |
| `ledger_entries` | ledger | V8 |
| `ledger_lines` | ledger | V8 |
| `manual_review_cases` | risk | V32 |
| `membership_plans` | membership | V1 |
| `membership_tiers` | membership | V1 |
| `merchant_accounts` | merchant | V15 |
| `merchant_api_keys` | merchant | V33 |
| `merchant_daily_kpi_projections` | reporting | V30 |
| `merchant_feature_flags` | platform/ops | V10 |
| `merchant_modes` | merchant | V33 |
| `merchant_settings` | merchant | V15+V34 |
| `merchant_users` | merchant | V15 |
| `merchant_webhook_deliveries` | notifications | V28+V48 |
| `merchant_webhook_endpoints` | notifications | V28+V48 |
| `ops_timeline_events` | reporting/ops | V43 |
| `outbox_events` | outbox | V10+V29+V47 |
| `payment_attempts` | payments | V20+V37 |
| `payment_intents_v2` | payments | V20 |
| `payment_method_mandates` | payments | V19 |
| `payment_methods` | payments | V19 |
| `payment_summary_projection` | reporting/ops | V42 |
| `payments` | membership | V1+V4 |
| `price_versions` | catalog | V17 |
| `prices` | catalog | V17 |
| `products` | catalog | V17 |
| `rate_limit_events` | platform | V36 |
| `recon_dashboard_projection` | reporting/ops | V42 |
| `recon_mismatches` | recon | V12+V31 |
| `refresh_tokens` | membership | V2 |
| `refunds_v2` | payments/refund | V25+V46 |
| `repair_actions_audit` | platform/repair | V40 |
| `revenue_recognition_schedules` | ledger/revenue | V24+V41+V45 |
| `revenue_waterfall_projection` | ledger/revenue | V45 |
| `risk_decisions` | risk | V32 |
| `risk_events` | risk | V11 |
| `risk_rules` | risk | V32 |
| `settlement_batch_items` | recon | V25 |
| `settlement_batches` | recon | V25 |
| `statement_imports` | recon | V31 |
| `subscription_history` | membership | V14 |
| `subscription_payment_preferences` | dunning | V27 |
| `subscription_schedules` | subscription | V18 |
| `subscription_status_projection` | reporting/ops | V42 |
| `subscriptions` | membership | V1 |
| `subscriptions_v2` | subscription | V18 |
| `support_cases` | support | V49 |
| `support_notes` | support | V49 |
| `tax_profiles` | billing/tax | V23 |
| `token_blacklist` | membership | V3 |
| `users` | membership | V1 |
| `webhook_deliveries` | membership | V10 |
| `webhook_endpoints` | membership | V10 |


---

## 6. Module Analyses

### 6.1 Membership — Auth, JWT, V1 Subscriptions

**Purpose**: Identity foundation for the entire platform. Manages user accounts, JWT-based authentication, token refresh/revocation, and the original V1 membership plans and subscriptions. Superseded by multi-tenant modules (Merchant + Customer + Subscription V2) for new features, but still required for existing V1 users and admin auth.

**Entities**

| Entity | Key Fields | Notes |
|---|---|---|
| `User` | id, email, password_hash(BCrypt), role(ADMIN/USER), failed_login_count, last_login_at | Platform-level user |
| `MembershipTier` | id, name, level(INT) | Hierarchy anchor: BRONZE=1, SILVER=2, GOLD=3 |
| `MembershipPlan` | id, name, tier_id, billing_cycle, price, features_json | Configures V1 subscriptions |
| `Subscription` (V1) | id, user_id, plan_id, status, start_date, end_date, auto_renew | Simple lifecycle, status-only |
| `RefreshToken` | id, user_id, token, expires_at, revoked | 7-day sliding window |
| `TokenBlacklist` | id, token, blacklisted_at, expires_at, reason | For logout/revocation |

**Controllers & Key Endpoints**

| Controller | Endpoint | Purpose |
|---|---|---|
| `AuthController` | `POST /auth/register` | Register new user; BCrypt password |
| | `POST /auth/login` | Authenticate; returns access JWT + refresh token |
| | `POST /auth/refresh` | Exchange refresh token for new access JWT |
| | `POST /auth/logout` | Blacklist current token |
| `UserController` | `GET /users/{id}` | Fetch user profile |
| | `PUT /users/{id}` | Update profile |
| | `GET /admin/users` | List all users (ADMIN only) |
| `MembershipController` | `GET /membership/tiers` | List tiers |
| | `GET /membership/plans` | List plans |
| `SubscriptionController` | `POST /subscriptions` | Create V1 subscription |
| | `GET /subscriptions/{id}` | Fetch subscription |
| | `PUT /subscriptions/{id}/cancel` | Cancel |
| `PlanController` | CRUD `/plans` | Manage membership plans (ADMIN) |

**Core Business Logic**

1. **Registration**: Email uniqueness enforced at DB level. Password BCrypt-hashed at service layer. Role defaults to USER.
2. **Login**: Failed login counter incremented on auth failure; account lockout policy available but threshold configurable.
3. **JWT issuance**: Short-lived access token (15 min, HS256) + long-lived refresh token (7d, UUID stored in DB).
4. **Token refresh**: Validates refresh token is not revoked and not expired. Issues new access token; refresh token rotation is NOT enforced (same token can refresh multiple times until expiry or explicit revocation).
5. **Logout**: Adds access token to `token_blacklist`; refresh token marked `revoked=true`. `JwtAuthFilter` checks blacklist on every request.
6. **V1 subscriptions**: Date-range based; no state machine; `end_date` is the termination marker. No proration, no invoice generation. **Legacy module — not recommended for new integrations.**

**Gaps & Limitations**

- No OAuth2 / OIDC — custom JWT only
- No multi-factor authentication
- No email verification flow
- No password reset flow
- No account lockout after N failures (counter exists but no enforcement logic visible)
- Refresh token rotation not enforced (security concern)
- TokenBlacklist grows unbounded (no TTL purge job)
- V1 subscriptions have no invoice or payment linkage beyond the `payments` table

---

### 6.2 Merchant — Multi-Tenancy, API Keys, Sandbox/Live Modes

**Purpose**: Multi-tenant isolation. Every entity in the system (customers, subscriptions, invoices, payments) is scoped to a `merchant_id`. This module manages merchant account lifecycle, user access (RBAC lite), configuration, API keypair management, and Sandbox↔Live mode switching.

**Entities**

| Entity | Key Fields | Notes |
|---|---|---|
| `MerchantAccount` | id, name, legal_name, email, status | Root tenant entity |
| `MerchantUser` | id, merchant_id, user_id, role | Junction: one user can belong to multiple merchants |
| `MerchantSettings` | id, merchant_id(UNIQUE), settlement_frequency, auto_retry_enabled, max_payment_retry_count, tax_inclusive_pricing, timezone, locale | Merchant-level system configuration |
| `MerchantApiKey` | id, merchant_id, mode, key_prefix(16 hex, public), key_hash(SHA-256, private), is_active | Keypair for API authentication |
| `MerchantMode` | id, merchant_id(UNIQUE), current_mode(SANDBOX/LIVE), live_enabled_at | Mode state |

**Controllers & Key Endpoints**

| Endpoint | Purpose |
|---|---|
| `POST /merchants` | Create merchant account |
| `GET /merchants/{id}` | Fetch merchant profile |
| `PUT /merchants/{id}` | Update merchant |
| `POST /merchants/{id}/users` | Add user to merchant with role |
| `DELETE /merchants/{id}/users/{userId}` | Remove user |
| `GET /merchants/{id}/settings` | Get configuration |
| `PUT /merchants/{id}/settings` | Update configuration |
| `POST /merchants/{id}/api-keys` | Generate API keypair (returns plaintext key once only) |
| `DELETE /merchants/{id}/api-keys/{keyId}` | Revoke key |
| `POST /merchants/{id}/mode/switch` | Switch Sandbox ↔ Live |

**Core Business Logic**

1. **API Key Generation**: Key = UUID bytes hex-encoded. `key_prefix` = first 16 chars (stored plaintext for O(1) DB lookup). `key_hash` = SHA-256 of full key (stored for verification). The plaintext key is returned exactly once on creation — never retrievable again.
2. **API Key Verification**: Filter looks up by `key_prefix`, then validates SHA-256 hash matches. No timing-safe comparison in standard JPA `equals()` — potential timing oracle (minor risk on self-hosted).
3. **Mode Switching**: SANDBOX → LIVE requires admin authorization. `merchant_modes.live_enabled_at` is set on first live activation. Mode gates which gateway credentials are used.
4. **RBAC**: Three roles — `READ_ONLY` (GET only), `OPERATOR` (transactional writes), `ADMIN` (configuration + key management). Enforced via Spring Security `@PreAuthorize` annotations.
5. **Settings**: `default_dunning_policy_code` links to `dunning_policies`. `default_grace_days` used when no policy specifies grace period.

**Gaps & Limitations**

- No webhook on merchant status change
- No merchant onboarding workflow (KYC/KYB)
- API key timing-safe comparison not explicitly implemented
- No per-key scope restrictions (all keys have same permissions based on merchant role)
- No key expiry (keys are valid until explicitly revoked)
- No audit log on role changes
- RBAC lite — only 3 roles, no fine-grained permission sets

---

### 6.3 Customer — Billable Identity, PII Encryption

**Purpose**: Represents a billable end-user within a merchant's tenant. Decoupled from platform `User` — a customer exists in the context of a merchant and carries PII encrypted at the column level. Customer notes provide a lightweight annotation layer.

**Entities**

| Entity | Key Fields | Notes |
|---|---|---|
| `Customer` | id, merchant_id, email, name, phone(ENCRYPTED), billing_address(ENCRYPTED), shipping_address(ENCRYPTED), external_customer_id, status, metadata_json | Billable identity; PII encrypted |
| `CustomerNote` | id, customer_id, content, visibility(INTERNAL_ONLY/MERCHANT_VISIBLE), created_by_user_id, created_at | Immutable — no update path |

**Encryption**: `EncryptedStringConverter` implements JPA `AttributeConverter<String, String>`. Uses AES-256-GCM with a per-write random nonce (96-bit IV). Nonce is prepended to the ciphertext and Base64-encoded for storage. Key is loaded from `application.properties` (`app.encryption.key`).

**Controllers & Key Endpoints**

| Endpoint | Purpose |
|---|---|
| `POST /merchants/{merchantId}/customers` | Create customer |
| `GET /merchants/{merchantId}/customers` | List with pagination |
| `GET /merchants/{merchantId}/customers/{id}` | Fetch customer (PII auto-decrypted on read) |
| `PUT /merchants/{merchantId}/customers/{id}` | Update customer |
| `POST /merchants/{merchantId}/customers/{id}/block` | Block customer |
| `POST /merchants/{merchantId}/customers/{id}/notes` | Add note |
| `GET /merchants/{merchantId}/customers/{id}/notes` | List notes filtered by visibility |

**Gaps & Limitations**

- No GDPR erasure endpoint (right to be forgotten)
- No audit log of which user accessed PII
- Encryption key is a static application property — no key rotation mechanism
- No customer deduplication across merchants (same email can create customers in multiple merchants)
- No customer merge/link workflow
- Customer search relies on exact email match — full-text search deferred to admin/search module

---

### 6.4 Catalog — Products, Prices, Versioned Pricing

**Purpose**: Defines what can be sold and at what price. Products are merchant-scoped items. Prices define recurring/one-time billing parameters. Price versions allow price changes without breaking existing subscriptions (via `grandfather_existing_subscriptions` flag).

**Entities**

| Entity | Key Fields | Notes |
|---|---|---|
| `Product` | id, merchant_id, product_code(UNIQUE per merchant), name, status(ACTIVE/ARCHIVED) | What is being sold |
| `Price` | id, product_id, merchant_id, billing_type(RECURRING/ONE_TIME), billing_interval_unit, billing_interval_count, trial_days, currency, is_active | How it's billed |
| `PriceVersion` | id, price_id, unit_amount, currency, effective_from, effective_to, grandfather_existing_subscriptions | Immutable price history |

**Versioning Logic**

When a price change is needed:
1. A new `PriceVersion` is created with `effective_from = now()` and `effective_to = null`.
2. The previous version gets `effective_to = now()`.
3. If `grandfather_existing_subscriptions = true`, existing subscriptions on the old version continue at the old price.
4. New subscriptions pick up the latest active `PriceVersion` at subscription creation time.

The overlap constraint (`no two versions with overlapping [effective_from, effective_to) per price_id`) is enforced at the DB/service layer.

**Controllers & Key Endpoints**

| Endpoint | Purpose |
|---|---|
| `POST /merchants/{merchantId}/products` | Create product |
| `GET /merchants/{merchantId}/products` | List products (ACTIVE/ARCHIVED filter) |
| `PUT /merchants/{merchantId}/products/{id}` | Update product |
| `POST /products/{productId}/prices` | Create price for product |
| `GET /products/{productId}/prices` | List prices |
| `POST /prices/{priceId}/versions` | Create new price version |
| `GET /prices/{priceId}/versions` | List version history |

**Gaps & Limitations**

- **No metered billing / usage-based pricing** — only flat recurring/one-time billing
- **No tiered pricing** (e.g., first 10 units at $X, next 90 at $Y)
- **No volume pricing** or graduated billing
- **No multi-currency per price** — single currency per Price record
- **No trial period at version level** — trial is on Price, not PriceVersion
- Product archive does not cascade to subscriptions (active subscriptions on archived products continue)
- Price versioning does not support `effective_from` in the future (pre-scheduled price changes)


---

### 6.5 Subscription V2 — State Machine, Schedules, OCC

**Purpose**: The authoritative subscription lifecycle engine for the multi-tenant platform. Manages subscription state transitions through an explicit state machine, handles billing period advancement, and supports future scheduled actions (upgrade/downgrade/cancel/pause/resume).

**State Machine: Allowed Transitions**

```
INCOMPLETE  ──► TRIALING     (trial started)
INCOMPLETE  ──► ACTIVE       (immediate activation)
INCOMPLETE  ──► CANCELLED    (cancel before activation)

TRIALING    ──► ACTIVE       (trial converted)
TRIALING    ──► CANCELLED    (cancel during trial)
TRIALING    ──► PAST_DUE     (first payment failed)

ACTIVE      ──► PAST_DUE     (renewal payment failed)
ACTIVE      ──► PAUSED       (merchant-initiated pause)
ACTIVE      ──► CANCELLED    (cancel requested)
ACTIVE      ──► EXPIRED      (end_date reached without renewal)

PAST_DUE    ──► ACTIVE       (dunning succeeded)
PAST_DUE    ──► SUSPENDED    (dunning exhausted, not yet cancelled)
PAST_DUE    ──► CANCELLED    (customer/merchant cancelled while past due)

PAUSED      ──► ACTIVE       (resume)
PAUSED      ──► CANCELLED    (cancel while paused)

SUSPENDED   ──► ACTIVE       (payment collected, reactivated)
SUSPENDED   ──► CANCELLED    (final cancellation)

CANCELLED   ──► (terminal, no transitions)
EXPIRED     ──► (terminal, no transitions)
```

**`SubscriptionStateMachine`** validates every transition before it is applied. Any attempt to move to a state not in the allowed map throws `INVALID_SUBSCRIPTION_TRANSITION` (400).

**Optimistic Concurrency Control**: `subscriptions_v2.version` incremented on every write. `@Version` on the JPA entity causes Hibernate to include `WHERE version = ?` in UPDATE statements. `OptimisticLockException` surfaced as HTTP 409 Conflict.

**Subscription Schedules**: Future-dated actions stored in `subscription_schedules`. A scheduler polls `PENDING` schedules with `execute_at <= now()` and applies the scheduled action. Once executed, status set to `EXECUTED`. Manual cancellation sets to `CANCELLED`.

**Core Business Logic Flow (Subscription Creation)**

1. Validate customer + product + price belong to same merchant
2. Resolve active `PriceVersion` at creation time
3. Create `SubscriptionV2` in `INCOMPLETE` state
4. If `trial_days > 0`, immediately transition to `TRIALING` and set `trial_end = now() + trial_days`
5. Else transition to `ACTIVE`, set `current_period_start = now()`, `current_period_end = billing_anchor_at + one_interval`
6. Publish outbox event `SUBSCRIPTION_ACTIVATED` (triggers invoice generation handler)

**Gaps & Limitations**

- No credit carry-forward on pause/resume (paused period is not prorated back to customer)
- No mid-cycle plan change (must schedule UPGRADE/DOWNGRADE for next period)
- No trial extension mechanism
- No multiple concurrent schedules validation (e.g., two CANCEL schedules for same subscription)
- Billing anchor drift not handled (e.g., Feb 30 → always anchors to last day of month)

---

### 6.6 Billing — Invoices, Discounts, Proration, Credit Notes

**Purpose**: Financial document engine. Generates invoices from subscription periods, applies discounts and credits, calculates proration for mid-cycle changes, and produces credit notes for adjustments.

**Entities**

| Entity | Key Fields | Notes |
|---|---|---|
| `Invoice` | id, merchant_id, subscription_id, invoice_number, status(DRAFT/PENDING/PAID/VOID/UNCOLLECTIBLE), subtotal, discount_total, credit_total, tax_total, grand_total | Main financial document |
| `InvoiceLine` | id, invoice_id, description, amount, quantity, unit_price, line_type | Typed line items |
| `Discount` | id, merchant_id, code, type(FIXED/PERCENTAGE), amount_off, percent_off, max_redemptions | Coupon/promo code |
| `DiscountRedemption` | id, discount_id, customer_id, invoice_id, amount_applied | Tracks usage |
| `CreditNote` | id, merchant_id, invoice_id, amount, reason, status | Issued against paid invoice |
| `InvoiceSequence` | id, merchant_id, prefix, current_number | Sequential invoice numbering per merchant |

**Invoice Line Types**: `PLAN_CHARGE`, `PRORATION`, `DISCOUNT`, `TAX`, `CGST`, `SGST`, `IGST`, `CREDIT_APPLIED`

**`InvoiceTotalServiceImpl` — Grand Total Calculation**

```
subtotal      = sum(lines WHERE type IN (PLAN_CHARGE, PRORATION))
discountTotal = sum(lines WHERE type = DISCOUNT)
creditTotal   = sum(lines WHERE type = CREDIT_APPLIED)
taxTotal      = sum(lines WHERE type IN (TAX, CGST, SGST, IGST))
grandTotal    = max(0, subtotal - discountTotal - creditTotal + taxTotal)
```

Grand total floored at 0 — no negative invoices. If credits/discounts exceed subtotal + tax, the excess is lost (not carried forward). This is a gap: no credit carry-forward mechanism.

**`ProrationCalculator`**

Calculates prorated PLAN_CHARGE or PRORATION line amount for mid-cycle changes:
```
Used days     = periodEnd - changeDate (exclusive)
Total days    = periodEnd - periodStart
Prorated amt  = (usedDays / totalDays) * planAmount
```
Used for: cancellations mid-cycle, plan upgrades/downgrades, free trial conversion.

**`InvoiceNumberService`** selects `invoice_sequences` row FOR UPDATE, increments `current_number`, and generates `prefix + zero-padded-number` (e.g., `FC-0000123`). Per-merchant prefix.

**Controllers & Key Endpoints**

| Endpoint | Purpose |
|---|---|
| `POST /merchants/{mId}/invoices` | Create invoice |
| `GET /merchants/{mId}/invoices` | List invoices (status, customer filters) |
| `GET /merchants/{mId}/invoices/{id}` | Fetch with lines |
| `POST /merchants/{mId}/invoices/{id}/finalize` | Move DRAFT → PENDING |
| `POST /merchants/{mId}/invoices/{id}/void` | Void invoice (creates ledger reversal) |
| `POST /merchants/{mId}/discounts` | Create discount code |
| `POST /merchants/{mId}/discounts/{code}/validate` | Check validity + redemption count |
| `POST /merchants/{mId}/invoices/{id}/apply-discount` | Redeem discount on invoice |
| `POST /merchants/{mId}/credit-notes` | Issue credit note |
| `POST /merchants/{mId}/invoices/{id}/apply-credit` | Apply credit note to invoice |

**Gaps & Limitations**

- No credit carry-forward (excess credits on an invoice are lost)
- No dunning-aware invoice finalization (finalize before or after dunning?)
- Invoice void does not automatically trigger a refund
- No line-level discount (only invoice-level discount codes)
- No recurring discount (code applied once per invoice unless per_customer_limit > 1)
- No invoice templates or PDF generation
- Invoice status machine not fully formalized (no `GlobalExceptionHandler` guard on illegal status changes)

---

### 6.7 Tax — India GST (CGST/SGST/IGST)

**Purpose**: Calculates India-specific GST on invoices based on merchant + customer tax profiles. Handles both B2B (GSTIN-to-GSTIN) and B2C transactions, same-state (CGST+SGST) vs cross-state (IGST) determination.

**Entities**

| Entity | Key Fields |
|---|---|
| `TaxProfile` | id, merchant_id(UNIQUE), gstin, legal_state_code, is_tax_registered |
| `CustomerTaxProfile` | id, customer_id(UNIQUE), gstin, is_tax_exempt, state_code, entity_type |

**Tax Calculation Logic (`TaxCalculationService`)**

1. Load merchant `TaxProfile` and customer `CustomerTaxProfile`
2. If `customer.is_tax_exempt = true` → no tax lines added
3. If `merchant.is_tax_registered = false` → no tax lines added
4. Else:
   - If merchant `legal_state_code == customer state_code` → CGST + SGST (each at half the GST rate)
   - Else → IGST (full GST rate)
5. GST rate sourced from merchant settings / plan metadata (e.g., 18% for software services)
6. Adds appropriate line type (`CGST`, `SGST`, or `IGST`) to invoice

**Gaps & Limitations**

- **India-only** — no TaxJar, Avalara, or multi-country tax support
- GST rate is not dynamically fetched from HSN/SAC code tables
- No GST return filing automation (GSTN API)
- No reverse charge mechanism (RCM) support
- No e-invoice generation (IRP portal upload)
- No e-way bill support
- Customer tax exemption is a boolean flag — no exemption certificate storage

---

### 6.8 Payments V2 — Core Intents, Methods, Mandates, Attempts

**Purpose**: Orchestrates payment execution. A `PaymentIntentV2` represents a customer's intent to pay a specific amount. `PaymentAttempt` records each gateway call. `PaymentMethod` and `PaymentMethodMandate` manage payment instruments and recurring authorization.

**`PaymentIntentV2` Lifecycle**

```
REQUIRES_PAYMENT_METHOD  ──► REQUIRES_CONFIRMATION   (payment method attached)
REQUIRES_CONFIRMATION    ──► PROCESSING              (confirmation submitted)
PROCESSING               ──► SUCCEEDED               (gateway callback: success)
PROCESSING               ──► REQUIRES_ACTION         (3DS challenge needed, theoretical)
PROCESSING               ──► CANCELLED               (gateway callback: failure + no retry)
SUCCEEDED                ──► (terminal)
CANCELLED                ──► (terminal)
```

**`PaymentAttemptService` — Attempt Execution Flow**

1. Resolve routing via `PaymentRoutingService` (see 6.9)
2. Store routing snapshot in `payment_attempts.routing_snapshot_json`
3. Call `PaymentGateway.charge(intent, attempt, gateway)` — currently `SimulatedPaymentGateway`
4. Record attempt: increment `attempt_number`, store `gateway_response_code`, `failure_reason`, `failure_category`, `latency_ms`
5. On SUCCESS: update intent status → SUCCEEDED; post ledger entry (DEBIT RECEIVABLE / CREDIT REVENUE)
6. On FAILURE: evaluate `is_retriable`; if retriable and retry count < max → schedule next dunning attempt; else → CANCELLED

**`SimulatedPaymentGateway`** (hexagonal port adapter):
- Simulates success/failure based on test card numbers
- No real network calls
- Card ending in 0000 → always SUCCESS, 0001 → always FAIL, others → random based on configured success rate

> **CRITICAL GAP**: There is no production payment gateway adapter. This is an educational/internal platform only. Integrating Razorpay, Stripe, or PayU would require implementing the `PaymentGateway` interface and handling webhooks from those providers.

**`PaymentMethod`**:
- `provider_token` is a gateway-issued token (not a raw PAN — PCI scope is maintained)
- `fingerprint` for deduplication (prevents saving same card twice)
- `UNIQUE(provider, provider_token)` constraint — but a customer can save the same card via different providers

**Controllers & Key Endpoints**

| Endpoint | Purpose |
|---|---|
| `POST /merchants/{mId}/payment-intents` | Create payment intent |
| `POST /payment-intents/{id}/confirm` | Confirm with payment method |
| `POST /payment-intents/{id}/capture` | Manual capture (capture_mode=MANUAL) |
| `POST /payment-intents/{id}/cancel` | Cancel intent |
| `GET /payment-intents/{id}/attempts` | List all attempts |
| `POST /merchants/{mId}/customers/{cId}/payment-methods` | Store payment method |
| `GET /merchants/{mId}/customers/{cId}/payment-methods` | List saved methods |
| `DELETE /payment-methods/{id}` | Revoke payment method |
| `POST /payment-methods/{id}/mandates` | Create mandate |
| `DELETE /mandates/{id}` | Revoke mandate |

**Gaps & Limitations**

- SimulatedPaymentGateway only — no production integration
- No 3DS / SCA / EMV challenge flow
- No payment link / QR code generation
- No ACH/SEPA direct debit support
- No BNPL / installment payment support
- No partial capture support
- No multi-currency conversion (one currency per payment intent)
- Attempt `failure_category` classification logic limited to gateway response codes
- No automatic retry on transient network errors (distinguished from business failures)


---

### 6.9 Payment Routing — Gateway Selection, Health, Caching

**Purpose**: Determines which payment gateway to use for a given payment attempt. Selects based on configurable rules (merchant-specific first, then platform-wide fallback), respects gateway health status, and caches routing decisions in Redis.

**`PaymentRoutingServiceImpl` — Selection Algorithm**

1. Build query key: (merchant_id, method_type, currency, country_code, retry_number)
2. **Redis cache lookup**: key = `routing:{merchantId}:{methodType}:{currency}:{country}:{retry}`
   - Cache hit → return immediately, skip DB
   - Cache miss → proceed to DB
3. **Merchant-scoped rules**: Query `gateway_route_rules WHERE merchant_id = ? AND ...` ordered by priority ASC
4. **Platform-wide fallback**: If no merchant rules match → query `WHERE merchant_id IS NULL AND ...`
5. Walk rules in priority order, for each rule:
   - Check preferred gateway health via `GatewayHealthService`
   - If preferred gateway is `DOWN` → skip to next rule
   - If preferred gateway is `HEALTHY` or `DEGRADED` → use it
   - If fallback_gateway configured and preferred is DOWN → check fallback health
6. Store resolved rule in Redis cache with TTL
7. Return `RoutingResult` (preferred_gateway, fallback_gateway, matched_rule_id, is_merchant_scope)

**`GatewayHealthService`**: Queries `gateway_health` table. Health updated by background scheduler (`GatewayHealthChecker`) which calls a lightweight probe on each gateway at regular intervals. Status: `HEALTHY` / `DEGRADED` / `DOWN`.

**Routing Snapshot**: Full routing decision is serialized to JSON and stored in `payment_attempts.routing_snapshot_json`. This creates a complete audit trail of why a gateway was chosen, enabling post-hoc analysis of routing quality.

**Gaps & Limitations**

- No real-time health probe — health updated on a fixed schedule, not per-request
- No circuit breaker (e.g., Resilience4j) — DOWN health updated by scheduler, not on-the-fly
- Redis cache invalidation on rule change not implemented (TTL-based expiry only)
- No A/B testing / traffic splitting (e.g., 80% Razorpay, 20% Stripe)
- No currency-specific gateway preferences beyond rule configuration
- No cost-based routing (e.g., route to cheapest gateway for a given method type)
- `DEGRADED` status treated same as `HEALTHY` for routing purposes

---

### 6.10 Refunds V2 — Capacity Checks, Accounting

**Purpose**: Processes refund requests against captured payments, enforcing that refunds do not exceed the refundable capacity of the original payment intent.

**`RefundServiceV2` — Refund Flow**

1. Load `PaymentIntentV2`; validate status = SUCCEEDED
2. Calculate refundable capacity: `capturedAmount - refundedAmount - disputedAmount`
3. Validate refund amount ≤ capacity; throw `REFUND_EXCEEDS_REFUNDABLE` if over
4. Check `request_fingerprint` (SHA-256 over merchant_id + payment_id + amount + reason) for duplicate request dedup
5. Create `RefundV2` in PENDING status
6. Call `PaymentGateway.refund(paymentIntentId, amount)` (simulated)
7. On SUCCESS: update `payment_intents_v2.refunded_amount += amount`; status → COMPLETED
8. `RefundAccountingService.postRefundEntry()`: post ledger entry DEBIT REFUND_EXPENSE / CREDIT CASH

**`RefundAccountingService`** wraps ledger posting in `REQUIRES_NEW` to isolate from the refund transaction. Records a `BusinessEffectFingerprint` with type `REFUND_COMPLETED`.

**Gaps & Limitations**

- Partial refund requires multiple refund requests (no single partial-refund endpoint)
- No refund reversal (if a refund is accidentally issued, no undo path)
- No refund approval workflow (refunds execute immediately on request)
- No customer notification of refund (no email/SMS integration)
- `disputedAmount` deducted from refundable capacity — but no enforcement that disputed funds can't be refunded independently is visible

---

### 6.11 Disputes (Chargebacks) — Evidence, Reserve, Accounting

**Purpose**: Manages chargeback lifecycle from gateway notification through evidence submission to resolution (won/lost), with accurate ledger accounting for reserves and final settlements.

**`DisputeService` — Core Flow**

1. Dispute opened (webhook from gateway or manual entry): `status = OPEN`, set `due_by`, record `amount/currency`
2. `DisputeAccountingService.postReserveEntry()`: if `reserve_posted = false` → post ledger DEBIT DISPUTE_RESERVE / CREDIT CASH; set `reserve_posted = true` (idempotent guard on the flag)
3. Merchant submits evidence: create `DisputeEvidence` records with `evidence_type`, `file_url`, `description`
4. `DisputeEvidenceService.submitEvidence()`: validates evidence types, calls gateway submit (simulated)
5. Resolution (gateway decision):
   - WON: `DisputeAccountingService.postResolutionEntry(WON)` → DEBIT CASH / CREDIT DISPUTE_RESERVE (reversal); set `resolution_posted = true`
   - LOST: `DisputeAccountingService.postResolutionEntry(LOST)` → DEBIT CHARGEBACK_EXPENSE / CREDIT DISPUTE_RESERVE (write-off); set `resolution_posted = true`
6. `BusinessEffectFingerprint` type `DISPUTE_OPENED` recorded at step 1

**`reserve_posted` and `resolution_posted` flags** (V46): prevent double-posting of ledger entries on idempotent gateway webhook replays.

**Gaps & Limitations**

- No automated gateway webhook receiver for dispute notifications
- Evidence submission is simulated (no real gateway API call)
- No dispute timeline / history tracking (no status transition log)
- No notification to merchant when dispute is received
- No pre-arbitration / escalation handling
- `due_by` deadline is stored but no reminder/alert mechanism

---

### 6.12 Ledger — Double-Entry Bookkeeping

**Purpose**: All money movements in the system are recorded as double-entry ledger entries. Guards the fundamental accounting invariant that every entry is balanced (DEBIT sum == CREDIT sum). Provides balance querying by account.

**`LedgerService.postEntry()` — Core Flow**

1. Call `validateLines(lines)`:
   ```
   debitSum  = sum(lines WHERE side=DEBIT)
   creditSum = sum(lines WHERE side=CREDIT)
   if debitSum != creditSum:
     increment Micrometer counter: ledger_unbalanced_total
     throw LEDGER_UNBALANCED (HTTP 500)
   ```
2. Create `LedgerEntry` entity (entry_type, reference_type, reference_id, currency, memo)
3. Create `LedgerLine` entities for each line (account_id, side, amount)
4. All persisted in a single `@Transactional` call

**Chart of Accounts (COA) — Seeded at V8**

| Account Code | Name | Type | Normal Balance | Used For |
|---|---|---|---|---|
| `RECEIVABLE` | Accounts Receivable | ASSET | DEBIT | Invoice generated but not yet paid |
| `CASH` | Cash | ASSET | DEBIT | Payment received |
| `DISPUTE_RESERVE` | Dispute Reserve | ASSET | DEBIT | Funds held pending chargeback resolution |
| `REVENUE_SUBSCRIPTIONS` | Subscription Revenue | INCOME | CREDIT | Earned subscription revenue |
| `SUBSCRIPTION_LIABILITY` | Subscription Liability | LIABILITY | CREDIT | Deferred/unearned subscription revenue |
| `REFUND_EXPENSE` | Refund Expense | EXPENSE | DEBIT | Cost of issuing refunds |
| `CHARGEBACK_EXPENSE` | Chargeback Expense | EXPENSE | DEBIT | Cost of lost chargebacks |
| `SETTLEMENT` | Settlement | ASSET | DEBIT | Net settlement received from gateway |

**Standard Journal Entries**

```
Invoice issued:           DR RECEIVABLE        / CR SUBSCRIPTION_LIABILITY
Payment received:         DR CASH              / CR RECEIVABLE
                          DR SUBSCRIPTION_LIABILITY / CR REVENUE_SUBSCRIPTIONS (at recognition)
Refund issued:            DR REFUND_EXPENSE    / CR CASH
Dispute reserve:          DR DISPUTE_RESERVE   / CR CASH
Dispute won (reversal):   DR CASH              / CR DISPUTE_RESERVE
Dispute lost:             DR CHARGEBACK_EXPENSE/ CR DISPUTE_RESERVE
Settlement:               DR SETTLEMENT        / CR CASH
```

**`LedgerBalanceService`** computes running balance for an account:
- Normal balance = DEBIT for ASSET/EXPENSE; CREDIT for LIABILITY/INCOME
- Net balance = normal_side_sum - opposite_side_sum

**Controllers & Key Endpoints**

| Endpoint | Purpose |
|---|---|
| `GET /ledger/accounts` | List all COA accounts |
| `GET /ledger/accounts/{id}/balance` | Current balance |
| `GET /ledger/entries?referenceType=&referenceId=` | Entries for a reference (e.g., invoice) |
| `GET /ledger/entries/{id}` | Fetch entry with all lines |

**Gaps & Limitations**

- Only 8 hardcoded COA accounts — no dynamic COA management
- No multi-currency ledger entries (single currency per entry)
- No period closing / fiscal year management
- No chart of accounts hierarchy (no parent/child accounts)
- No trial balance or P&L report generation
- Ledger entries are immutable by design — no reversal entry API (reversal is manual via a new entry)
- Balance is computed on-the-fly (no indexed running total) — `O(n)` per account scan

---

### 6.13 Revenue Recognition — ASC 606 Schedules, Waterfall Projection

**Purpose**: Implements ASC 606 straight-line revenue recognition for subscription invoices. When an invoice is paid, deferred revenue (SUBSCRIPTION_LIABILITY) is amortized into recognized revenue (REVENUE_SUBSCRIPTIONS) ratably over the subscription period.

**`RevenueRecognitionPostingService` — Recognition Flow**

1. **Schedule Generation**: When invoice is finalized/paid, generate one `RevenueRecognitionSchedule` row per recognition period (daily or monthly) with `amount = invoice.grand_total / total_periods`, `status = PENDING`.
   - `generation_fingerprint` (V45) = SHA-256 of (invoice_id, period_dates) → prevents duplicate generation under concurrent calls
   - `UNIQUE(invoice_id, recognition_date)` enforced at DB level as second guard

2. **Daily Recognition Batch**: Scheduler polls `recognition_date <= today() AND status = PENDING` using SKIP LOCKED.
   - For each schedule: post ledger entry DEBIT SUBSCRIPTION_LIABILITY / CREDIT REVENUE_SUBSCRIPTIONS
   - Set `ledger_entry_id`, `status = POSTED`, `posting_run_id`, `catch_up_run = false`
   - OCC via `@Version` — concurrent schedulers race; loser gets `OptimisticLockException` and skips (idempotent)

3. **Catch-up Runs**: If recognition was missed (e.g., service downtime), a catch-up run posts all past-due PENDING schedules with `catch_up_run = true` for audit visibility.

**`RevenueWaterfallProjectionService`** (V45): Maintains a daily denormalized projection:
- `billed_amount`: invoices generated that day
- `deferred_opening`, `deferred_closing`: SUBSCRIPTION_LIABILITY balance at start/end of day
- `recognized_amount`: revenue recognized that day
- `refunded_amount`, `disputed_amount`: refunds/disputes that affected revenue
Used for revenue waterfall dashboard without querying ledger_entries in real time.

**Integrity Checkers for Revenue** (in `platform/integrity`):
- `NoDuplicateRevenuePostingChecker`: checks no two schedules posted to same ledger entry
- `PostedScheduleHasLedgerLinkChecker`: every POSTED schedule has a non-null ledger_entry_id
- `ScheduleTotalEqualsInvoiceAmountChecker`: sum of schedule amounts = invoice.grand_total (rounding tolerance ±1 cent)
- `RevenueRecognitionCeilingChecker` (ledger checker): total recognized revenue ≤ billed revenue

**Gaps & Limitations**

- Daily straight-line only — no usage-based or milestone-based recognition
- No recognition reversal on invoice void or credit note (must create manual ledger entry)
- No ASC 606 disclosure notes or GAAP report generation
- `catch_up_run` flag exists but no SLA alerting when recognition is missed
- Revenue waterfall projection is a T+1 view (built by nightly job)

---

### 6.14 Dunning — V1 Scheduler + V2 Policy-Driven Engine

**Purpose**: Automates failed payment retry for subscriptions. V1 is a simple scheduler; V2 is a fully configurable policy engine with per-merchant retry schedules, grace periods, backup payment method support, and configurable outcome after exhaustion.

**V2 Policy Structure (`DunningPolicy`)**

```json
{
  "code": "standard_retry",
  "retry_offsets_json": [1440, 4320, 10080],
  "max_attempts": 3,
  "grace_days": 7,
  "fallback_to_backup_payment_method": true,
  "status_after_exhaustion": "SUSPENDED"
}
```
- `retry_offsets_json`: minutes after initial failure (1440 = 1d, 4320 = 3d, 10080 = 7d)
- `grace_days`: days before subscription moves from PAST_DUE to exhaustion status
- `fallback_to_backup_payment_method`: if primary fails, try backup (`subscription_payment_preferences.backup_payment_method_id`)
- `status_after_exhaustion`: SUSPENDED or CANCELLED

**`DunningServiceV2Impl` — Retry Flow**

1. On payment failure: load subscription's dunning policy (`merchant_settings.default_dunning_policy_code` → `dunning_policies` table, fallback to platform default)
2. Create `DunningAttempt` for each scheduled retry offset: `scheduled_at = failedAt + offset_minutes`
3. Transition subscription to `PAST_DUE` via state machine
4. Scheduler (`DunningSchedulerV2`) polls `dunning_attempts WHERE status=SCHEDULED AND scheduled_at <= now()` using SKIP LOCKED
5. For each due attempt:
   a. Try primary payment method
   b. If fail AND `fallback_to_backup_payment_method = true` → try backup payment method
   c. Record attempt outcome
6. On SUCCESS: transition subscription PAST_DUE → ACTIVE; publish `PAYMENT_SUCCEEDED` outbox event
7. On final attempt failure:
   a. If `status_after_exhaustion = SUSPENDED` → transition PAST_DUE → SUSPENDED
   b. If `CANCELLED` → transition PAST_DUE → CANCELLED

**`RenewalService`**: Handles periodic renewal (billing period advancement): generates new invoice, triggers payment intent creation, schedules dunning if payment fails.

**`SubscriptionPaymentPreference`**: Stores `primary_payment_method_id` + `backup_payment_method_id` + `retry_order_json` for a subscription. Dunning V2 reads this to determine payment method fallback order.

**Gaps & Limitations**

- No smart retry (e.g., skip retry on `do_not_retry` failure codes like `stolen_card`)
- `retry_offsets_json` is static per policy — no dynamic adjustment based on failure reason
- No email/SMS notification at dunning stage (no integration with notification service)
- V1 and V2 schedulers coexist — V2 only fires for subscriptions with `dunning_policy_id` set; V1 remains for legacy subscriptions
- No WebSocket push to merchant dashboard on dunning state change
- Dunning attempt `attempt_number` resets across policies if policy changes

---

### 6.15 Outbox — Transactional Messaging & Dead-Letter Queue

**Purpose**: Reliable event publishing using the Transactional Outbox pattern. Events written to `outbox_events` in the same transaction as business data changes. A poller delivers them to handlers asynchronously. Failed events are retried with exponential backoff and moved to a DLQ after exhaustion.

**Configuration Constants**

```
MAX_ATTEMPTS        = 5
BACKOFF_MINUTES     = [5, 15, 30, 60]   (index = attempt_count - 1, capped at last)
STALE_LEASE_MINUTES = 5                  (recover events where processing_owner died)
```

**`OutboxService` — Processing Flow**

```
OutboxScheduler  polls every 10s:
  SELECT id FROM outbox_events
  WHERE status IN ('NEW','FAILED')
    AND next_attempt_at <= NOW()
  FOR UPDATE SKIP LOCKED
  LIMIT 50

For each event:
  1. Set processing_owner = hostname, processing_started_at = now(), status = PROCESSING
  2. Look up handler in OutboxEventHandlerRegistry by event_type
  3. DedupAwareOutboxHandler: check handler_fingerprint (SHA-256 of event_type + payload hash)
     - Already processed → mark PROCESSED (idempotent skip)
  4. Execute handler (REQUIRES_NEW transaction)
  5. On success: status = PROCESSED, processed_at = now()
  6. On failure:
     - attempt_count++
     - next_attempt_at += BACKOFF_MINUTES[attempt_count - 1]
     - failure_category = classify(exception)
     - If attempt_count >= MAX_ATTEMPTS: status = DEAD; create DeadLetterMessage
```

**Stale Lease Recovery**: Separate job polls `WHERE status = PROCESSING AND processing_started_at < NOW() - 5 minutes`. Resets to NEW for retry. This handles the case where the processing node died mid-execution.

**Registered Handlers**

| Handler | Triggered By |
|---|---|
| `InvoiceCreatedHandler` | `INVOICE_CREATED` event |
| `PaymentSucceededHandler` | `PAYMENT_SUCCEEDED` event |
| `RefundIssuedHandler` | `REFUND_ISSUED` event |
| `SubscriptionActivatedHandler` | `SUBSCRIPTION_ACTIVATED` event |

**Dead Letter Queue (`DlqController`)**:
- `GET /dlq` — list dead letter messages
- `POST /dlq/{id}/retry` — manually re-queue to outbox
- `DELETE /dlq/{id}` — permanently discard

**Gaps & Limitations**

- No real message broker (Kafka/RabbitMQ/SQS) — DB-polling outbox only; throughput limited by DB poll rate
- `BACKOFF_MINUTES` array has 4 entries but `MAX_ATTEMPTS = 5` — 5th retry uses the same 60-min backoff (not a bug, just note)
- No per-event-type retry policy customization
- Handler registry lookup is type-string-match — no compile-time guarantee that all event types have a handler
- No DLQ alerting (no webhook/email when DLQ size exceeds threshold)
- No batch handler (events processed one at a time even if they could be batched)


---

### 6.16 Events — Domain Event Log, Correlation, Replay

**Purpose**: Provides a persistent, queryable log of all domain events emitted by the system. Enables debugging, audit trail reconstruction, and selective event replay for projection rebuilds or re-processing.

**`DomainEvent` Schema** (enhanced in V29)

| Field | Purpose |
|---|---|
| `event_type` | String identifier (e.g., `SUBSCRIPTION_ACTIVATED`) |
| `aggregate_type` | Entity type (e.g., `SubscriptionV2`) |
| `aggregate_id` | Entity primary key |
| `merchant_id` | Tenant scope |
| `event_version` | Version of the event schema |
| `schema_version` | Version of the payload schema |
| `correlation_id` | UUID tracing a user-initiated request across multiple events |
| `causation_id` | ID of the event that caused this event (causal chain) |
| `payload_json` | Full event payload |
| `recorded_at` | Wall clock time of recording |

**Correlation vs Causation**: A single user action (e.g., subscription activation) records one event with a `correlation_id` = the request ID. That event's `causation_id` = the ID of the triggering event (e.g., payment succeeded). This enables full causal chain reconstruction.

**`ReplayService`**: Allows replaying events by aggregate_type + aggregate_id range or by event_type + date range. Replay pushes events back through the outbox for handler re-execution. Used for:
- Projection rebuild (replay all `SUBSCRIPTION_ACTIVATED` events to rebuild subscription_status_projection)
- Re-processing after handler bug fix

**Integrity Checkers**:
- `CausationCorrelationIntegrityChecker`: For events with causation_id, verifies a parent event with that ID exists
- `DomainEventMetadataChecker`: Verifies no events have null aggregate_id or event_type

**Gaps & Limitations**

- Events are stored but not streamed (no Kafka/EventBridge — DB only)
- No event sourcing (aggregates are rebuilt from current DB state, not event history)
- No event schema registry (schema_version is a soft integer, no Avro/Protobuf validation)
- Replay is manual — no automated projection staleness detection
- No event compaction / archival strategy (domain_events will grow unboundedly)
- correlation_id is set at event creation but not propagated through HTTP response headers (no `X-Correlation-Id` standardization visible in controllers)

---

### 6.17 Notifications — Signed Merchant Webhooks, Auto-Disable

**Purpose**: Delivers platform events to merchant-registered webhook endpoints via HTTPS POST with HMAC-SHA256 body signing. Implements auto-disable on consecutive failures to prevent hammering dead endpoints.

**Delivery Flow**

1. Business event triggers `MerchantWebhookDeliveryService.scheduleDelivery(eventType, payload)`
2. Creates `MerchantWebhookDelivery` with `status = PENDING` for each active endpoint subscribed to that event
3. `WebhookDeliveryScheduler` polls SKIP LOCKED: `status IN ('PENDING','FAILED') AND next_attempt_at <= NOW()`
4. `HttpWebhookDispatcher.dispatch(delivery, endpoint)`:
   - Serialize payload JSON
   - Compute signature: `X-FirstClub-Signature: sha256=HMAC-SHA256(endpoint.secret, body)`
   - POST to `endpoint.url` with 5-second timeout
   - On 2xx response: status = DELIVERED; `consecutive_failures = 0` (reset)
   - On non-2xx or timeout: status = FAILED; `attempt_count++`; `consecutive_failures++`
5. **Auto-disable check**: if `consecutive_failures >= DISABLE_THRESHOLD` → set `auto_disabled_at = NOW()`, `is_active = false`
6. `processing_owner` + `processing_started_at` used for stale lease recovery (same pattern as outbox)
7. `delivery_fingerprint` (V48) = SHA-256 of delivery_id + attempt_count → prevents duplicate delivery on concurrent scheduler instances

**HMAC Signature Verification** (merchant-side): Merchant computes `HMAC-SHA256(shared_secret, raw_body)` and compares to header value. Raw body must be verified before JSON parsing to prevent canonicalization attacks.

**Gaps & Limitations**

- Webhook secret is stored hashed in DB (good for security) but the hashing algorithm and whether the full secret or just a derived key is used deserves clarity
- No per-endpoint retry policy (all endpoints share same retry cadence)
- No webhook log UI for merchant to see recent deliveries and debug
- No mTLS option for high-security webhook consumers
- Auto-disable threshold is hardcoded — not merchant-configurable
- No batch webhook delivery (one delivery per endpoint per event; no fan-out batching)
- Webhook payload schema versioning not implemented

---

### 6.18 Risk — V1 Basic + V2 Rule Engine, Manual Review

**Purpose**: Two-tier fraud and risk system. V1 is simple (IP blocklist + velocity). V2 is a configurable rule engine with pluggable evaluators, a scoring pipeline, and a 4-level decision: ALLOW / CHALLENGE / REVIEW / BLOCK.

**V1 `RiskService`**

- IP blocklist check: query `ip_blocklist` → if found → BLOCKED
- Velocity check: count `risk_events WHERE ip_address = ? AND created_at > now() - 1 hour` → if ≥ 5 → VELOCITY_EXCEEDED
- All risk events persisted in `REQUIRES_NEW` transaction (survives outer transaction rollback)

**V2 `RiskDecisionService` — Decision Pipeline**

1. Load all active `RiskRule` entities for merchant (+ platform-wide `merchant_id IS NULL` rules)
2. Sort by priority ASC
3. For each rule, dispatch to appropriate `RuleEvaluator.evaluate(rule, context)`:

| Evaluator | What it checks | Config fields |
|---|---|---|
| `BlocklistIpEvaluator` | IP in `ip_blocklist` table | — |
| `IpVelocityEvaluator` | Count of risk_events by IP in last N minutes | `window_minutes`, `max_count` in config_json |
| `UserVelocityEvaluator` | Payment attempts by user in last N minutes | `window_minutes`, `max_count` |
| `DeviceReuseEvaluator` | Payment method fingerprint seen on multiple users | `max_users_per_fingerprint` |

4. For each matched rule: accumulate `score += rule.config_json.score`; record `matched_rule`
5. Determine final decision: **BLOCK** takes precedence over REVIEW, REVIEW over CHALLENGE, CHALLENGE over ALLOW
   - Any matched rule with action=BLOCK → final = BLOCK (early exit)
   - Else: highest-severity action among matched rules
6. Persist `RiskDecision` with score + matched_rules_json
7. If decision = REVIEW → `ManualReviewService.openCase(paymentIntentId)`
8. Return decision to payment flow: BLOCK → reject payment; REVIEW → hold for manual; CHALLENGE → request 3DS (theoretical — no 3DS implemented); ALLOW → proceed

**`ManualReviewService`**: Creates `ManualReviewCase` OPEN; supports assign (to ops user), approve, reject, escalate.

**Gaps & Limitations**

- **No ML risk scoring** — rule-based only
- No real-time feature store (velocity computed from DB scan, not Redis counters)
- Score is informational only — decision is based on matched action, not score threshold
- `DeviceReuseEvaluator` flags shared fingerprints — but shared devices (family plan) would be false positives
- No adaptive risk (failed payment attempts don't automatically tighten risk threshold)
- No risk analytics dashboard
- Blocklist has no automatic expiry (permanent blocks only)
- No geolocation or velocity-by-country rules
- Manual review has no SLA tracking or alerting on aging cases

---

### 6.19 Reconciliation — 4-Layer Advanced Recon

**Purpose**: Ensures financial correctness across four data layers: platform payments ↔ platform ledger ↔ gateway settlement batches ↔ external bank statements. Identifies mismatches, assigns them to owners, and tracks resolution.

**4 Reconciliation Layers**

| Layer | What is compared | Mismatch type |
|---|---|---|
| L1 | `payment_intents_v2` (SUCCEEDED) ↔ `ledger_entries` (CASH) | Payment not journaled |
| L2 | `ledger_entries` (CASH) ↔ `settlement_batches` | Ledger entry not in settlement |
| L3 | `settlement_batches` ↔ `external_statement_imports` | Batch not on bank statement |
| L4 | Net settlement amounts ↔ expected settlement amounts | Settlement amount mismatch |

**`AdvancedReconciliationService.runLayered(merchantId, reconDate)`**

1. L1: For each SUCCEEDED payment_intent_v2 on reconDate, check linked ledger_entry exists → record mismatch WHERE missing
2. L2: For each CASH ledger_entry, check it appears in a settlement_batch_item → mismatch WHERE absent
3. L3: Parse `statement_imports` for reconDate, reconcile against settlement_batches → mismatch WHERE absent
4. L4: Sum settlement_batches.net_amount vs statement_imports.total_amount by gateway → record amount diff

`recon_mismatches.layer` field tags each mismatch with its layer (2/3/4 — L1 has its own original structure).

**`ReconMismatch` Assignment**: `owner_user_id` field allows assigning mismatches to ops team members. `resolved_by` + `resolution_note` capture close details.

**`ExternalStatementImport`**: Ops team uploads bank statement CSVs via `StatementImportController`. The import is parsed into `statement_imports` record + line items for Layer 3/4 matching.

**`NightlyReconScheduler`**: Runs L1-L4 for the previous day automatically.

**Integrity Checkers**:
- `BatchUniquenessChecker`: No two settlement batches for same (merchant, gateway, date)
- `PaymentInAtMostOneBatchChecker`: No payment appears in more than one settlement batch
- `ReconRerunIdempotencyChecker`: Running recon twice for same date produces identical mismatch set

**Gaps & Limitations**

- No automated bank statement parsing (CSV format assumed; multi-format support not implemented)
- No real-time recon (nightly only)
- L1 mismatch detection is point-in-time — timing differences (T+0 vs T+1 settlement) generate false mismatches
- No dispute amount reconciliation across layers
- No cross-currency settlement reconciliation (FX conversion not supported)
- `ReconReport` entity exists but no PDF/CSV export implementation visible
- No Slack/email alert when recon finds high-severity mismatches


---

### 6.20 Reporting — Projections, KPI, Ops Dashboard, Timeline

**Purpose**: Provides pre-computed read models for dashboards and reporting. All projections are updated event-driven (on business events) or through scheduled rebuilds. No ad-hoc query layer — all reporting is pre-materialized.

**Projection Types**

| Projection | Granularity | Updated By | Key Metrics |
|---|---|---|---|
| `CustomerBillingSummaryProjection` | Per (merchant, customer) | On payment/invoice events | active_subs, unpaid_invoices, total_paid, total_refunded |
| `MerchantDailyKpiProjection` | Per (merchant, date) | Nightly rebuild | new_subs, cancelled_subs, success/fail payments, gross/net revenue, refunds |
| `LedgerBalanceSnapshot` | Per (account, date, merchant) | Nightly `LedgerSnapshotService` | opening_balance, closing_balance, period_debits/credits |
| `SubscriptionStatusProjection` | Per (merchant, status) | On subscription state change | subscription_count per status |
| `InvoiceSummaryProjection` | Per (merchant, status) | On invoice status change | invoice_count, total_amount per status |
| `PaymentSummaryProjection` | Per (merchant, status) | On payment status change | payment_count, total_amount per status |
| `ReconDashboardProjection` | Per merchant | After recon runs | open_mismatches, resolved_mismatches, total_amount_off, last_recon_at |
| `RevenueWaterfallProjection` | Per (merchant, business_date) | Nightly revenue job | billed, deferred_open/close, recognized, refunded, disputed |

**`ProjectionUpdateService`**: Event-driven incremental updates. Each business event triggers a targeted upsert to the relevant projection rows. More efficient than full rebuild — O(1) update per event.

**`ProjectionRebuildService`**: Full projection rebuild from source data. Used for:
- Initial setup
- After data correction
- When event-driven updates are suspected to be inconsistent

**`TimelineService`** (V43): Records `OpsTimelineEvent` for any significant state change on an entity:
- `entity_type` + `entity_id` → polymorphic reference
- `event_type` → what happened
- `event_data_json` → snapshot of key fields at the time
- `severity` → for filtering critical events
- Enables a "history" view for any entity (subscription, invoice, payment, dispute)

**`LedgerSnapshotService`**: Nightly job computes running balance for each ledger account and stores in `ledger_balance_snapshots`. Enables O(1) balance query (instead of O(n) scan of ledger_lines) for recent dates.

**`SearchCacheService`** (in reporting): Maintains a Redis-backed cache layer for the unified search module, refreshed when underlying data changes.

**Controllers & Key Endpoints**

| Endpoint | Purpose |
|---|---|
| `GET /merchants/{mId}/reporting/kpi` | Daily KPI metrics |
| `GET /merchants/{mId}/reporting/billing-summary` | Per-customer billing summary |
| `GET /merchants/{mId}/reporting/ledger-snapshots` | Daily ledger balance history |
| `GET /merchants/{mId}/reporting/ops/subscription-status` | Subscription status breakdown |
| `GET /merchants/{mId}/reporting/ops/invoice-summary` | Invoice status breakdown |
| `GET /merchants/{mId}/reporting/ops/payment-summary` | Payment status breakdown |
| `GET /merchants/{mId}/reporting/ops/recon-dashboard` | Recon health projection |
| `GET /merchants/{mId}/reporting/revenue-waterfall` | Revenue recognition waterfall |
| `GET /merchants/{mId}/timeline/entity/{entityType}/{entityId}` | Entity event history |
| `POST /reporting/projections/rebuild` | Trigger full rebuild (ADMIN) |

**Gaps & Limitations**

- No real-time streaming (all T+0 or T+1)
- No ad-hoc query / filter / group-by API
- No CSV / Excel export for any report
- No scheduled email delivery of reports
- Timeline events only forward — no historical event backfill before V43 migration
- Revenue waterfall is T+1 (nightly) — no intraday view
- No cohort analysis (e.g., subscription retention by signup month)
- No funnel analytics (conversion rate from INCOMPLETE → TRIALING → ACTIVE)

---

### 6.21 Platform — Idempotency, Rate Limiting, Redis, Integrity Engine, Repair, Dedup, Concurrency, OPS

**Purpose**: Cross-cutting infrastructure that all domain modules depend on. Provides safety rails for distributed operation: exactly-once semantics, rate control, data integrity validation, self-healing repair actions, business-effect deduplication, concurrency guards, feature flags, and operational health endpoints.

#### 6.21a Idempotency (`platform/idempotency`)

**Two-Tier Architecture**:
- **Tier 1 (Redis NX lock)**: `SET idempotency:{merchantId}:{key} "locked" NX PX 30000` — acquired before DB lookup. Fast; prevents double-execution under concurrent identical requests.
- **Tier 2 (DB persistence)**: `idempotency_keys` table stores request hash, response body, status code. Enables long-term dedup (Redis may evict; DB is durable).

**`IdempotencyFilter`** (Servlet filter, runs before controllers):
1. Extract `Idempotency-Key` header; if absent → pass through (no dedup)
2. Build composite key = `{merchantId}:{rawKey}`
3. Build `endpoint_signature` = `{METHOD}:{url-template}` (e.g., `POST:/merchants/{merchantId}/invoices`)
4. Acquire Redis NX lock
5. DB lookup: if found AND `endpoint_signature` matches → return cached response (HTTP 200/same code)
6. If found AND `endpoint_signature` differs → HTTP 409 Conflict (same key, different endpoint — programming error)
7. If not found → create placeholder row, release lock, proceed with request
8. After handler completes → update row with response body + status code
9. Release locks

#### 6.21b Rate Limiting (`platform/ratelimit`)

**`RedisSlidingWindowRateLimiter`** — Lua script based on Redis sorted sets:

```lua
local key      = KEYS[1]           -- e.g., "ratelimit:PAYMENT_INTENT:merchant:42"
local now      = tonumber(ARGV[1]) -- milliseconds since epoch
local window   = tonumber(ARGV[2]) -- window size in ms
local limit    = tonumber(ARGV[3]) -- max requests
local requestId = ARGV[4]          -- unique ID for this request

ZREMRANGEBYSCORE(key, 0, now - window)   -- remove expired entries
local count = ZCARD(key)                  -- current count in window

if count >= limit then
  return {0, 0, TTL}                      -- blocked; returns [allowed=0, remaining=0, resetMs]
end
ZADD(key, now, requestId)                 -- record this request
PEXPIRE(key, window)                      -- auto-expire the key
return {1, limit - count - 1, TTL}        -- allowed; returns [1, remaining, resetMs]
```

**`RateLimitFilter`**: Runs before controllers. Extracts `category` (e.g., PAYMENT_INTENT_CREATE) and `subjectKey` (merchant_id or IP) from request. Calls Lua script. On blocked: writes `RateLimitEvent` and returns HTTP 429 with `Retry-After` header.

**Graceful degradation**: If Redis is unavailable → rate limit check is bypassed (allow-by-default). Rate limit audit event still attempted.

#### 6.21c Redis Infrastructure (`platform/redis`)

**`RedisHealthIndicator`**: Spring Actuator health check; marks Redis as DOWN on connection failure.
**`RedisAvailabilityChecker`**: Application-level check used by rate limiter and idempotency to degrade gracefully.
**TTL-keyed namespaces**: each Redis key category has a configured TTL to prevent unbounded growth.

#### 6.21d Integrity Engine (`platform/integrity`)

**22 `InvariantChecker` implementations** across 6 domain areas:

**Billing (5 checkers)**:
| Checker | Verifies |
|---|---|
| `CreditApplicationChecker` | Applied credit_note amounts don't exceed original credit_note amount |
| `DiscountTotalConsistencyChecker` | `invoice.discount_total` == sum of DISCOUNT lines on invoice |
| `InvoicePeriodOverlapChecker` | No two ACTIVE invoices for same subscription with overlapping periods |
| `InvoiceTotalEqualsLineSumChecker` | `invoice.grand_total` matches `InvoiceTotalService.compute(invoice)` |
| `TerminalInvoiceImmutabilityChecker` | VOID/PAID invoices have no mutations after terminal status |

**Events (2 checkers)**:
| Checker | Verifies |
|---|---|
| `CausationCorrelationIntegrityChecker` | Events with causation_id → parent event exists |
| `DomainEventMetadataChecker` | No null aggregate_id or event_type |

**Ledger (4 checkers)**:
| Checker | Verifies |
|---|---|
| `LedgerEntryBalancedChecker` | DEBIT == CREDIT for every ledger_entry |
| `NoDuplicateJournalChecker` | No two entries for same (reference_type, reference_id, entry_type) |
| `RevenueRecognitionCeilingChecker` | Total REVENUE_SUBSCRIPTIONS credit ≤ total SUBSCRIPTION_LIABILITY credit |
| `SettlementAmountChecker` | settlement_batches.net_amount == sum of batch_items |

**Payments (7 checkers)**:
| Checker | Verifies |
|---|---|
| `AttemptNumberMonotonicChecker` | attempt_number increases monotonically per intent |
| `DisputeReservePostedIntegrityChecker` | All disputes with reserve_posted=true have linked ledger entry |
| `DisputeResolutionPostedIntegrityChecker` | All CLOSED disputes have resolution_posted=true |
| `OneSuccessEffectPerPaymentIntentChecker` | No payment intent has >1 PAYMENT_CAPTURE_SUCCESS fingerprint |
| `RefundCumulativeIntegrityChecker` | Sum of refunds per intent ≤ captured_amount |
| `RefundWithinRefundableAmountChecker` | Individual refund ≤ refundable capacity at creation time |
| `TerminalIntentNoNewAttemptsChecker` | No new attempts created after SUCCEEDED/CANCELLED intent |

**Recon (3 checkers)**:
| Checker | Verifies |
|---|---|
| `BatchUniquenessChecker` | No duplicate (merchant, gateway, batch_date) settlement batches |
| `PaymentInAtMostOneBatchChecker` | No payment appears in >1 settlement batch |
| `ReconRerunIdempotencyChecker` | Running recon twice for same scope = same mismatch set |

**Revenue (3 checkers)**:
| Checker | Verifies |
|---|---|
| `NoDuplicateRevenuePostingChecker` | No two revenue schedules post to same ledger entry |
| `PostedScheduleHasLedgerLinkChecker` | POSTED schedules have non-null ledger_entry_id |
| `ScheduleTotalEqualsInvoiceAmountChecker` | Sum of schedule amounts = invoice.grand_total (±1 cent tolerance) |

**`IntegrityRunService`**: Runs all or a subset of checkers, persists results in `integrity_check_runs` + `integrity_check_findings`. Severity CRITICAL/HIGH for blocking issues; MEDIUM/LOW for informational. `suggested_repair_key` links directly to the repair action that can fix the violation.

#### 6.21e Repair Actions (`platform/repair`)

**7 `RepairAction` implementations**:

| Repair Key | What it does |
|---|---|
| `invoice.recompute` | Recomputes invoice totals from line items; updates grand_total |
| `ledger.snapshot.rebuild` | Rebuilds ledger balance snapshots for account(s); replaces stale snapshots |
| `outbox.event.retry` | Re-queues a DEAD outbox event back to NEW for retry |
| `projection.rebuild` | Triggers full projection rebuild for specified projection type |
| `recon.run` | Runs reconciliation for specified merchant + date |
| `revenue.schedule.regenerate` | Regenerates missing/corrupted recognition schedules for an invoice |
| `webhook.delivery.retry` | Re-queues GAVE_UP webhook deliveries back to PENDING |

**`RepairAuditService`**: All repair executions create immutable `repair_actions_audit` rows. Supports `dry_run=true` mode: records what would happen without executing. Enables safe preview before applying repairs to production data.

**Controller**: `POST /admin/repair/{repairKey}` (ADMIN role): executes repair with request body `{targetType, targetId, reason, dryRun}`.

#### 6.21f Business Effect Fingerprints (`platform/dedup`)

**`BusinessFingerprintService.recordEffect(effectType, businessKey, referenceType, referenceId)`**:
1. Compute SHA-256 of (effectType + "|" + businessKey string)
2. Attempt INSERT into `business_effect_fingerprints(effect_type, fingerprint, ...)`
3. ON UNIQUE CONFLICT (effect_type, fingerprint) → throw `DUPLICATE_BUSINESS_EFFECT` (409)

Used by: payment capture, refund creation, dispute open, settlement batch creation, revenue recognition posting. Prevents double-processing of the same business effect even if the same event is delivered multiple times.

#### 6.21g Concurrency (`platform/concurrency`)

**`ConcurrencyGuard`**: Application-level mutex using `job_locks` table. `BusinessLockScope` enum defines lock granularity (e.g., per-subscription, per-invoice, per-merchant).

Used when SKIP LOCKED alone is insufficient (e.g., when two operations that don't share a SELECT need mutual exclusion).

#### 6.21h OPS Platform (`platform/ops`)

- **Feature Flags**: `FeatureFlagService` checks `feature_flags` (platform-wide) or `merchant_feature_flags` (per-merchant). Used to gate new features without code deployment.
- **Job Locks**: `JobLockService` acquires/releases distributed locks for scheduled jobs. Uses `lock_expires_at` for automatic TTL-based recovery if the lock holder dies.
- **`DeepHealthController`** (`GET /actuator/health/deep`): Runs smoke checks across DB, Redis, gateway health, outbox backlog, DLQ size.
- **`DlqOpsController`**: Admin view/retry/discard for Dead Letter Queue entries.
- **`SystemHealthController`**: Aggregates Redis health, DB pool stats, outbox backlog, recent rate limit violations into a single health dashboard response.

---

### 6.22 Admin / Search — Unified Cross-Entity Search

**Purpose**: Provides a single search endpoint that queries across multiple entity types (customers, products, invoices, payment intents, subscriptions, merchants) and returns ranked results.

**`SearchService.search(query, merchantId, resultTypes)`**:
1. Normalize query: trim, lowercase, escape special chars for PostgreSQL `LIKE`/tsquery
2. Fan out to entity-specific sub-queries in parallel (Spring `CompletableFuture` or sequential depending on result limit)
3. Score and merge results: exact matches ranked higher than substring matches
4. `SearchCacheService`: Redis cache keyed on `(merchantId, normalizedQuery, resultTypes)` with SHORT TTL (30s) — primarily for burst dedup, not persistent caching

**`SearchResultType`** enum: `CUSTOMER`, `PRODUCT`, `INVOICE`, `PAYMENT_INTENT`, `SUBSCRIPTION`, `MERCHANT`

V44 adds PostgreSQL composite indexes and `to_tsvector` GIN indexes on the most common search columns to support performant text search.

**Endpoint**: `GET /admin/search?q={query}&merchantId={id}&types=CUSTOMER,INVOICE`

**Gaps & Limitations**

- No fuzzy/typo-tolerant search (exact LIKE prefix)
- No relevance-scored full-text search (PostgreSQL text search is keyword match, not vector similarity)
- No cross-field search (can't search for "Jane 2024-01" across name + invoice fields simultaneously)
- Search results are paginated but no cursor-based deep pagination
- Cache TTL is short — repeated identical queries within 30s are cached, but burst abuse possible

---

### 6.23 Support — Cases, Notes, Polymorphic Entity Linking

**Purpose**: Lightweight internal ticketing system for operations teams. Support cases can be linked to any platform entity (subscription, invoice, payment intent, dispute, customer) via polymorphic reference.

**Entities**

| Entity | Key Fields | Notes |
|---|---|---|
| `SupportCase` | id, merchant_id, linked_entity_type, linked_entity_id, title, status, priority, owner_user_id | Ticket with polymorphic FK |
| `SupportNote` | id, support_case_id, content, visibility, created_by_user_id, created_at | Immutable note (no update) |

**Status lifecycle**: `OPEN` → `IN_PROGRESS` → `RESOLVED` → `CLOSED`
**Priority levels**: `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`
**Note visibility**: `INTERNAL_ONLY` (ops team only) / `MERCHANT_VISIBLE` (visible to merchant ops)

**`SupportCaseService`**:
- `createCase(request)`: Creates case linked to any entity; validates entity exists
- `assignCase(id, userId)`: Assigns owner
- `updateStatus(id, status)`: Validates legal status transition
- `addNote(caseId, noteRequest)`: Creates immutable note

**Controllers & Key Endpoints**

| Endpoint | Purpose |
|---|---|
| `POST /merchants/{mId}/support/cases` | Create case for a merchant |
| `GET /merchants/{mId}/support/cases` | List cases (filter by status/priority) |
| `POST /support/cases/{id}/assign` | Assign to user |
| `PUT /support/cases/{id}/status` | Update status |
| `POST /support/cases/{id}/notes` | Add note |
| `GET /support/cases/{id}/notes` | List notes (visibility-filtered) |

**Gaps & Limitations**

- No SLA tracking (CRITICAL cases have no response-time SLA enforcement)
- No auto-escalation on case age
- No customer (merchant) portal for support cases — internal ops only
- Notes are immutable but no soft delete
- No full-text search within support notes
- No integration with external ticketing systems (Zendesk, Freshdesk, Jira)
- No attachment/file upload support for evidence


---

## 7. Test Suite Analysis

### 7.1 Summary

| Metric | Value |
|---|---|
| Total tests | **1374** |
| Failures | **0** |
| Build status | **BUILD SUCCESS** |
| Test runner | JUnit 5 + Mockito |
| Integration test infrastructure | Testcontainers (PostgreSQL 16) |
| Redis in tests | Embedded / Testcontainers Redis |

### 7.2 Test Distribution by Module (estimated)

| Module | Test count (approx.) | Test types |
|---|---|---|
| membership (auth, V1 subs) | ~80 | Unit (service) + Integration (controller) |
| merchant (accounts, API keys, modes) | ~90 | Unit + Integration |
| customer (PII encryption) | ~60 | Unit (encryption converter) + Integration |
| catalog (products, pricing, versions) | ~75 | Unit + Integration |
| subscription V2 (state machine) | ~100 | Unit (state machine transitions) + Integration |
| billing (invoices, totals, proration, discounts) | ~130 | Unit (calculator) + Integration (controller) |
| billing/tax (GST calculation) | ~40 | Unit (CGST/SGST/IGST routing) |
| payments V2 (intents, methods, mandates) | ~110 | Unit + Integration |
| payments/routing (gateway selection) | ~50 | Unit (rule evaluation, health check) |
| payments/refunds | ~45 | Unit (capacity, fingerprint) |
| payments/disputes | ~40 | Unit + Integration |
| ledger (double-entry, balance) | ~70 | Unit (validateLines) + Integration |
| ledger/revenue (recognition, waterfall) | ~60 | Unit (schedule generation) + Integration |
| dunning (V1 + V2) | ~65 | Unit (policy loading, retry scheduling) |
| outbox (processing, DLQ) | ~55 | Unit (retry logic, backoff) |
| events (domain log, replay) | ~30 | Unit |
| notifications/webhooks | ~45 | Unit (HMAC signing, auto-disable) |
| risk (V1 + V2 rules, evaluators) | ~60 | Unit (evaluators, pipeline) |
| recon (4-layer, matchers) | ~55 | Integration (recon against test data) |
| reporting (projections, timeline) | ~50 | Integration (projection update flow) |
| platform (idempotency, rate limit, integrity) | ~80 | Unit (Lua Lua script mock) + Integration |
| admin/search | ~25 | Integration (search against test data) |
| support (cases, notes) | ~30 | Unit + Integration |
| infrastructure / config | ~25 | Integration smoke tests |

### 7.3 Test Quality Observations

**Strengths**:
- Testcontainers ensures tests run against real PostgreSQL (not H2 dialect mismatch)
- State machine tests cover all legal transitions + all illegal ones (negative tests present)
- Integrity checkers each have dedicated unit tests with violation injection
- `InvoiceTotalService` tests cover all line type combinations including edge cases (zero grand total floor)
- `ProrationCalculator` tests cover partial months, leap years, period boundaries
- `EncryptedStringConverter` tests verify round-trip AES-256-GCM with different nonces
- Idempotency tests cover conflict detection (same key, different endpoint_signature)
- Rate limiter tests use Lua script against real Redis (not mocked)

**Areas for Improvement**:
- No performance/load tests (stress_test.py and user_stress_test.py in root are Python scripts, not JUnit)
- Master API test suite (`master_api_tests.py`) is a Python Requests-based integration test, not part of Maven test lifecycle
- No chaos/fault injection tests for Redis unavailability degradation path
- No contract tests (Pact or Spring Cloud Contract) for the PaymentGateway interface port
- Dunning integration tests rely on SimulatedPaymentGateway — no real gateway WebhookController test
- Timeline event tests may be sparse given the module is new (V43)

---

## 8. API Reference Summary

### Base URL
- Development: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI spec: `http://localhost:8080/v3/api-docs`

### Authentication
- **JWT Bearer**: `Authorization: Bearer {access_token}` — for user-context endpoints
- **API Key**: `X-Api-Key: {merchant_api_key}` — for merchant-scoped endpoints
- **Idempotency**: `Idempotency-Key: {unique_key}` — for mutation endpoints (optional but recommended)

### Core Endpoint Groups

#### Auth & Users (`/auth`, `/users`)
| Method | Path | Description |
|---|---|---|
| POST | `/auth/register` | Register platform user |
| POST | `/auth/login` | Login; returns JWT + refresh token |
| POST | `/auth/refresh` | Refresh access token |
| POST | `/auth/logout` | Blacklist current token |
| GET | `/users/{id}` | Get user profile |
| PUT | `/users/{id}` | Update user profile |

#### Merchants (`/merchants`)
| Method | Path | Description |
|---|---|---|
| POST | `/merchants` | Create merchant |
| GET | `/merchants/{id}` | Get merchant |
| PUT | `/merchants/{id}` | Update merchant |
| POST | `/merchants/{id}/users` | Add merchant user |
| GET | `/merchants/{id}/settings` | Get settings |
| PUT | `/merchants/{id}/settings` | Update settings |
| POST | `/merchants/{id}/api-keys` | Generate API key |
| DELETE | `/merchants/{id}/api-keys/{keyId}` | Revoke API key |
| POST | `/merchants/{id}/mode/switch` | Switch SANDBOX/LIVE |

#### Customers (`/merchants/{mId}/customers`)
| Method | Path | Description |
|---|---|---|
| POST | `/merchants/{mId}/customers` | Create customer |
| GET | `/merchants/{mId}/customers` | List customers (paginated) |
| GET | `/merchants/{mId}/customers/{id}` | Get customer (PII decrypted) |
| PUT | `/merchants/{mId}/customers/{id}` | Update customer |
| POST | `/merchants/{mId}/customers/{id}/notes` | Add customer note |

#### Catalog (`/merchants/{mId}/products`, `/products/{pId}/prices`)
| Method | Path | Description |
|---|---|---|
| POST | `/merchants/{mId}/products` | Create product |
| GET | `/merchants/{mId}/products` | List products |
| PUT | `/merchants/{mId}/products/{id}` | Update product |
| POST | `/products/{pId}/prices` | Add price to product |
| POST | `/prices/{priceId}/versions` | New price version |
| GET | `/prices/{priceId}/versions` | Price version history |

#### Subscriptions V2 (`/subscriptions`)
| Method | Path | Description |
|---|---|---|
| POST | `/subscriptions` | Create subscription |
| GET | `/subscriptions/{id}` | Get subscription |
| POST | `/subscriptions/{id}/cancel` | Cancel subscription |
| POST | `/subscriptions/{id}/pause` | Pause subscription |
| POST | `/subscriptions/{id}/resume` | Resume subscription |
| POST | `/subscriptions/{id}/schedules` | Schedule future action |

#### Invoices & Billing (`/merchants/{mId}/invoices`)
| Method | Path | Description |
|---|---|---|
| POST | `/merchants/{mId}/invoices` | Create invoice |
| GET | `/merchants/{mId}/invoices/{id}` | Get invoice with lines |
| POST | `/merchants/{mId}/invoices/{id}/finalize` | DRAFT → PENDING |
| POST | `/merchants/{mId}/invoices/{id}/void` | Void invoice |
| POST | `/merchants/{mId}/invoices/{id}/apply-discount` | Apply discount code |
| POST | `/merchants/{mId}/discounts` | Create discount |
| POST | `/merchants/{mId}/credit-notes` | Issue credit note |

#### Payments V2 (`/payment-intents`, `/payment-methods`)
| Method | Path | Description |
|---|---|---|
| POST | `/merchants/{mId}/payment-intents` | Create payment intent |
| POST | `/payment-intents/{id}/confirm` | Confirm with payment method |
| POST | `/payment-intents/{id}/capture` | Manual capture |
| POST | `/payment-intents/{id}/cancel` | Cancel intent |
| GET | `/payment-intents/{id}/attempts` | List attempts with routing snapshots |
| POST | `/merchants/{mId}/customers/{cId}/payment-methods` | Store payment method |
| POST | `/payment-methods/{id}/mandates` | Create mandate |

#### Refunds & Disputes
| Method | Path | Description |
|---|---|---|
| POST | `/merchants/{mId}/refunds` | Create refund |
| GET | `/merchants/{mId}/refunds/{id}` | Get refund |
| POST | `/merchants/{mId}/disputes` | Record dispute |
| POST | `/disputes/{id}/evidence` | Submit evidence |
| POST | `/disputes/{id}/resolve` | Resolve dispute (WON/LOST) |

#### Ledger (`/ledger`)
| Method | Path | Description |
|---|---|---|
| GET | `/ledger/accounts` | List COA accounts |
| GET | `/ledger/accounts/{id}/balance` | Account balance |
| GET | `/ledger/entries` | Entries for reference |
| GET | `/ledger/entries/{id}` | Entry + lines |

#### Dunning (`/dunning-policies`, `/dunning-attempts`)
| Method | Path | Description |
|---|---|---|
| POST | `/merchants/{mId}/dunning-policies` | Create dunning policy |
| GET | `/merchants/{mId}/dunning-policies` | List policies |
| GET | `/subscriptions/{id}/dunning` | Dunning attempts for subscription |

#### Risk (`/risk`)
| Method | Path | Description |
|---|---|---|
| POST | `/risk/evaluate` | Run risk evaluation for payment intent |
| POST | `/risk/ip-blocklist` | Add IP to blocklist |
| DELETE | `/risk/ip-blocklist/{ip}` | Remove IP |
| GET | `/merchants/{mId}/risk-rules` | List risk rules |
| POST | `/merchants/{mId}/risk-rules` | Create risk rule |
| GET | `/merchants/{mId}/manual-review` | List open review cases |
| POST | `/manual-review/{id}/approve` | Approve case |
| POST | `/manual-review/{id}/reject` | Reject case |

#### Reconciliation (`/recon`)
| Method | Path | Description |
|---|---|---|
| POST | `/merchants/{mId}/recon/run` | Run reconciliation for date |
| GET | `/merchants/{mId}/recon/mismatches` | List mismatches |
| POST | `/recon/mismatches/{id}/resolve` | Resolve mismatch |
| POST | `/merchants/{mId}/statement-imports` | Upload bank statement |
| GET | `/merchants/{mId}/settlement-batches` | List settlement batches |

#### Reporting (`/merchants/{mId}/reporting`)
| Method | Path | Description |
|---|---|---|
| GET | `/merchants/{mId}/reporting/kpi` | Daily KPI metrics |
| GET | `/merchants/{mId}/reporting/billing-summary` | Customer billing summary |
| GET | `/merchants/{mId}/reporting/revenue-waterfall` | Revenue waterfall |
| GET | `/merchants/{mId}/reporting/ops/subscription-status` | Subscription breakdown |
| GET | `/merchants/{mId}/timeline/entity/{type}/{id}` | Entity history timeline |

#### Platform & Admin
| Method | Path | Description |
|---|---|---|
| GET | `/actuator/health` | Standard Spring health |
| GET | `/actuator/health/deep` | Deep health (DB, Redis, outbox, DLQ) |
| GET | `/dlq` | Dead letter queue entries |
| POST | `/dlq/{id}/retry` | Re-queue DLQ entry |
| POST | `/admin/repair/{repairKey}` | Execute repair action |
| POST | `/admin/integrity/run` | Trigger integrity check run |
| GET | `/admin/integrity/runs` | List integrity check runs |
| GET | `/admin/integrity/runs/{id}/findings` | Findings for a run |
| GET | `/admin/search` | Unified cross-entity search |

#### Support (`/support`)
| Method | Path | Description |
|---|---|---|
| POST | `/merchants/{mId}/support/cases` | Create support case |
| GET | `/merchants/{mId}/support/cases` | List cases |
| POST | `/support/cases/{id}/notes` | Add note |
| POST | `/support/cases/{id}/assign` | Assign case |
| PUT | `/support/cases/{id}/status` | Update status |

#### Webhooks (`/merchants/{mId}/webhooks`)
| Method | Path | Description |
|---|---|---|
| POST | `/merchants/{mId}/webhooks/endpoints` | Register webhook endpoint |
| GET | `/merchants/{mId}/webhooks/endpoints` | List endpoints |
| DELETE | `/webhooks/endpoints/{id}` | Remove endpoint |
| GET | `/webhooks/endpoints/{id}/deliveries` | Delivery history |

---

## 9. Security Analysis

### 9.1 Authentication & Authorization

| Mechanism | Implementation | Assessment |
|---|---|---|
| Password storage | BCrypt | ✅ Secure (adaptive cost factor) |
| Access token | JWT HS256, 15-min expiry | ✅ Short-lived; HS256 acceptable for single-service |
| Refresh token | UUID, DB-stored, 7-day expiry | ✅ Revocable; stored in DB not just cookie |
| Token blacklist | Checked on every request via `JwtAuthFilter` | ✅ Immediate revocation on logout |
| API key storage | Plaintext prefix + SHA-256 hash | ✅ Prefix O(1) lookup; hash prevents recovery |
| Role-based access | `@PreAuthorize` on controllers | ✅ Spring Security enforcement |
| Multi-tenant isolation | `merchant_id` scoped repositories | ✅ All queries include merchant_id |

**Gaps**:
- No OAuth2/OIDC — JWT-only; no delegated auth
- Refresh token rotation not enforced (security best practice)
- No MFA support
- TokenBlacklist has no TTL purge (grows unbounded)
- API key timing-safe comparison not explicitly implemented (SHA-256 comparison via standard `equals()`)

### 9.2 Encryption & Data Protection

| Data Category | Protection |
|---|---|
| Customer phone | AES-256-GCM, per-write nonce, Base64-encoded |
| Customer billing_address | AES-256-GCM, per-write nonce |
| Customer shipping_address | AES-256-GCM, per-write nonce |
| Payment card PAN | Never stored (provider_token only) — PCI scope maintained |
| Passwords | BCrypt |
| Webhook secrets | Hashed (SHA-256 or similar) before storage |
| API keys | Key prefix plaintext, full key SHA-256-hashed |
| Encryption key | Static `app.encryption.key` property — no KMS integration |

**Gaps**:
- Encryption key in `application.properties` — no rotation mechanism, no KMS (AWS KMS/HashiCorp Vault)
- No column-level encryption for merchant email/phone
- No TLS enforcement in application config (assumed to be handled by infra layer)
- No field-level audit log showing who accessed PII

### 9.3 Input Validation & Injection Prevention

| Risk | Mitigation |
|---|---|
| SQL Injection | Spring Data JPA parameterized queries; native queries use `@Query` with `:param` binding |
| XSS | Spring MVC returns JSON (no HTML rendering); Jackson escapes special chars by default |
| Command injection | No shell command execution in the codebase |
| Path traversal | No file system access in business logic |
| SSRF | `HttpWebhookDispatcher` calls merchant-provided URLs — partial SSRF risk (see gap) |
| Mass assignment | DTO pattern used — JPA entities never deserialized from user input |

**SSRF gap**: `HttpWebhookDispatcher` makes HTTP calls to `merchant_webhook_endpoints.url`. An adversarial merchant could register `http://169.254.169.254/` (AWS metadata service) as a webhook endpoint. No URL allowlist or SSRF protection middleware is visible. **Recommendation**: Add URL validation to reject non-public IPs and metadata service endpoints.

### 9.4 API Security Posture

| Control | Status |
|---|---|
| Rate limiting | ✅ Sliding window per category/subject |
| Idempotency | ✅ Two-tier Redis + DB |
| CORS | ⚠️ Not explicitly configured — Spring defaults allow all origins in dev mode |
| Content-Type enforcement | ⚠️ Not enforced on all endpoints (`consumes` not set globally) |
| Sensitive data in GET params | ⚠️ `api-key` should not appear in query strings |
| Error responses | ✅ `GlobalExceptionHandler` returns structured JSON; no stack traces in production |
| Audit logging | ⚠️ `audit_log` table exists but not all operations write audit entries |

### 9.5 OWASP Top 10 Assessment

| OWASP Risk | Status | Notes |
|---|---|---|
| A01 - Broken Access Control | ✅ / ⚠️ | Multi-tenant isolation via merchant_id; but no row-level security at DB |
| A02 - Cryptographic Failures | ✅ | AES-256-GCM, BCrypt, SHA-256, HMAC-SHA256 all appropriate |
| A03 - Injection | ✅ | Parameterized queries; no dynamic SQL construction visible |
| A04 - Insecure Design | ⚠️ | Refresh token rotation not enforced; API key timing-safe comparison unclear |
| A05 - Security Misconfiguration | ⚠️ | CORS not hardened; no HTTPS enforcement in config |
| A06 - Vulnerable Components | ⚠️ | Spring Boot 3.4.3 (current but needs regular CVE scan) |
| A07 - Auth & Session Failures | ✅ / ⚠️ | JWT + blacklist good; no MFA; no account lockout enforcement |
| A08 - Software Integrity Failures | ✅ | Maven dependency management; no dynamic class loading |
| A09 - Security Logging | ⚠️ | Audit log table exists; not all sec events guaranteed to be logged |
| A10 - SSRF | ❌ | HttpWebhookDispatcher calls merchant-provided URLs without validation |

**SSRF is the most critical open vulnerability** — see section 9.3.


---

## 10. Feature Matrix

### 10.1 Authentication & Identity

| Feature | Status | Notes |
|---|---|---|
| Email + password registration | ✅ | BCrypt hashing |
| JWT access tokens | ✅ | HS256, 15-minute expiry |
| Refresh token flow | ✅ | UUID, 7-day, DB-stored |
| Token blacklist / logout | ✅ | Checked on every request |
| API key authentication | ✅ | Prefix + SHA-256 hash |
| API key SANDBOX/LIVE modes | ✅ | Separate key spaces |
| Multiple API keys per merchant | ✅ | Unlimited, named keys |
| OAuth2 / OIDC | ❌ | Not implemented |
| Multi-factor authentication | ❌ | Not implemented |
| Refresh token rotation | ❌ | Security best practice missing |
| API key expiration | ❌ | Keys do not expire |
| SSO / SAML | ❌ | Not implemented |

### 10.2 Multi-Tenancy

| Feature | Status | Notes |
|---|---|---|
| Merchant accounts | ✅ | Full CRUD |
| Merchant users with roles | ✅ | READ_ONLY / OPERATOR / ADMIN |
| SANDBOX / LIVE mode isolation | ✅ | Mode enum on all entities |
| Merchant-scoped API keys | ✅ | Per-merchant key management |
| Merchant settings / configuration | ✅ | Key-value store |
| Cross-merchant data isolation | ✅ | merchant_id on all repositories |
| Row-level security (DB-level) | ❌ | Application-level only |
| Merchant billing / platform fees | ❌ | No platform revenue module |
| Merchant-level rate limit tiers | ❌ | Platform-wide limits only |
| White-labeling / custom domains | ❌ | Not implemented |

### 10.3 Customer Management

| Feature | Status | Notes |
|---|---|---|
| Customer CRUD | ✅ | With PII encryption |
| Customer search | ✅ | Via unified admin search |
| PII field encryption (phone, address) | ✅ | AES-256-GCM per field |
| Customer notes | ✅ | Internal notes with author tracking |
| Customer metadata (key-value) | ✅ | JSONB metadata column |
| Customer tax profiles | ✅ | GSTIN, category, exemptions |
| Multiple payment methods per customer | ✅ | Vaulted tokens |
| Customer portal / self-service | ❌ | API only; no UI |
| GDPR erasure / right to be forgotten | ❌ | Not implemented |
| Customer merge / deduplication | ❌ | Not implemented |
| Customer lifetime value projection | ❌ | Not implemented |

### 10.4 Catalog

| Feature | Status | Notes |
|---|---|---|
| Product catalog | ✅ | CRUD with metadata |
| Price management | ✅ | Per-product prices |
| Price versioning | ✅ | Full version history |
| One-time prices | ✅ | Via billing_type enum |
| Recurring prices (fixed) | ✅ | MONTHLY / YEARLY / WEEKLY |
| Tiered / graduated pricing | ❌ | Not implemented |
| Volume pricing | ❌ | Not implemented |
| Metered / usage-based pricing | ❌ | Not implemented |
| Multi-currency pricing | ❌ | Single currency only |
| Price rounding rules | ❌ | Not implemented |

### 10.5 Subscriptions

| Feature | Status | Notes |
|---|---|---|
| Subscription creation | ✅ | With trial support |
| Trial periods | ✅ | `trial_end` date on subscription |
| Subscription state machine | ✅ | 8 states; all transitions validated |
| Pause / resume | ✅ | With reason and date tracking |
| Cancellation (immediate / end of period) | ✅ | Both modes |
| Schedule future subscription changes | ✅ | Phase-based scheduling |
| Subscription upgrades / downgrades | ✅ | Via schedule / plan change |
| Proration calculation | ✅ | `ProrationCalculator` full implementation |
| Billing anchor configuration | ✅ | Anchor date on subscription |
| Anniversary vs calendar billing | ⚠️ | Anchor-based but no normalization to 28th for Feb |
| Multi-plan subscriptions (bundles) | ❌ | Single plan per subscription |
| Subscription quantity | ❌ | No seat-based quantity |

### 10.6 Billing & Invoicing

| Feature | Status | Notes |
|---|---|---|
| Invoice creation | ✅ | DRAFT → PENDING → PAID → VOID |
| Invoice line items | ✅ | Multiple types (charge, tax, discount, credit) |
| Invoice finalization | ✅ | DRAFT → PENDING transition |
| Invoice voiding | ✅ | PENDING → VOID |
| Discount / coupon codes | ✅ | Amount and percentage discounts |
| Credit notes | ✅ | Issued against specific invoices |
| Credit balance | ✅ | Customer credit ledger balance |
| Proration credits | ✅ | Auto-applied on subscription change |
| Invoice total floor (zero minimum) | ✅ | Prevents negative invoices |
| Invoice PDF generation | ❌ | Not implemented |
| Dunning (retry on failure) | ✅ | Full V2 dunning with configurable policy |
| Metered billing / usage aggregation | ❌ | Not implemented |
| Advance invoicing | ❌ | Not implemented |
| Invoice templates | ❌ | Not implemented |

### 10.7 Tax

| Feature | Status | Notes |
|---|---|---|
| India GST (CGST + SGST + IGST) | ✅ | Interstate vs intrastate routing |
| GSTIN storage | ✅ | Customer and merchant GSTIN |
| Tax profiles per customer | ✅ | Category, exemption, override rates |
| Tax-exempt customers | ✅ | Exempt flag suppresses tax |
| GST calculation on invoice lines | ✅ | Applied at finalization |
| Reverse charge mechanism | ⚠️ | Column exists; not fully wired |
| E-invoice integration (GST portal) | ❌ | Not implemented |
| GSTR filing automation | ❌ | Not implemented |
| Multi-country tax | ❌ | India-only |
| TaxJar / Avalara integration | ❌ | Not implemented |
| Value-added tax (VAT) | ❌ | Not implemented |
| Sales tax (US) | ❌ | Not implemented |

### 10.8 Payments

| Feature | Status | Notes |
|---|---|---|
| Payment intent lifecycle | ✅ | REQUIRES_ACTION → SUCCEEDED/FAILED |
| Manual capture | ✅ | Authorize-then-capture flow |
| Payment method vault | ✅ | Provider tokens only (no raw PANs) |
| Mandate / standing instruction | ✅ | Mandate entity with status tracking |
| Multiple attempts per intent | ✅ | With gateway routing snapshot |
| Payment gateway abstraction | ✅ | `PaymentGatewayPort` interface |
| Simulated gateway (demo) | ✅ | `SimulatedPaymentGateway` |
| Production gateway (Stripe/Razorpay) | ❌ | No real adapter implemented |
| 3D Secure / SCA | ❌ | Not implemented |
| Partial capture | ❌ | Not implemented |
| Checkout hosted page / payment links | ❌ | Not implemented |
| ACH / SEPA direct debit setup | ❌ | Not implemented |
| EMV wallet (Apple Pay, Google Pay) | ❌ | Not implemented |

### 10.9 Routing

| Feature | Status | Notes |
|---|---|---|
| Rule-based payment routing | ✅ | Priority-ordered, condition-matched |
| Merchant-scope + platform-scope fallback | ✅ | Hierarchical rule application |
| Gateway health monitoring | ✅ | UP/DOWN/DEGRADED status |
| Unhealthy gateway skip | ✅ | Automatic skip in selection |
| Redis routing cache | ✅ | With health-aware selection |
| Routing snapshot stored on attempt | ✅ | Audit trail per attempt |
| Weighted random routing | ❌ | Not implemented |
| Latency-based routing | ❌ | Not implemented |
| A/B testing routing | ❌ | Not implemented |

### 10.10 Refunds

| Feature | Status | Notes |
|---|---|---|
| Full refunds | ✅ | Single-click full refund |
| Partial refunds | ✅ | Amount-controlled |
| Refund capacity checks | ✅ | Cannot exceed original charge |
| Business effect deduplication | ✅ | SHA-256 fingerprint |
| Refund accounting (ledger journal) | ✅ | Automatic DR/CR entries |
| Refund gateway dispatch | ✅ | Via PaymentGatewayPort |
| Refund to original payment method only | ✅ | Enforced on Mandate-required methods |
| Refund to different method | ❌ | Not implemented |
| Refund via original payment method not available | ❌ | No fallback to bank transfer |

### 10.11 Disputes

| Feature | Status | Notes |
|---|---|---|
| Dispute recording | ✅ | Dispute entity linked to payment |
| Dispute evidence submission | ✅ | Evidence entity + file references |
| Dispute resolution (WON/LOST) | ✅ | With accounting entries |
| Reserve fund withholding | ✅ | On dispute open |
| Reserve release on resolution | ✅ | WON: refund reserve; LOST: apply to loss |
| Dispute reason codes | ✅ | reason_code field on dispute |
| Dispute network deadline tracking | ✅ | `due_date` field on dispute |
| Chargeback representment workflow | ❌ | No formal workflow; manual only |
| Automated dispute response | ❌ | Not implemented |

### 10.12 Ledger & Revenue

| Feature | Status | Notes |
|---|---|---|
| Double-entry bookkeeping | ✅ | `LedgerEntry` + `LedgerLine` pair |
| Balanced entry validation | ✅ | `validateLines()` debit==credit |
| Chart of accounts (seeded) | ✅ | 8 accounts at V8 migration |
| Account balance queries | ✅ | Running balance per account |
| Standard journal entry templates | ✅ | 8 templates documented |
| Revenue recognition (ASC 606) | ✅ | Performance obligation tracking |
| Revenue recognition schedules | ✅ | PENDING → RECOGNIZING → RECOGNIZED |
| Over-/under-recognition catch-up | ✅ | `RecognitionCatchupService` |
| Revenue waterfall projection | ✅ | Future projected revenue by period |
| Multi-currency revenue recognition | ❌ | Single currency only |
| IFRS 15 compliance | ⚠️ | ASC 606 approach aligns but not explicitly labeled |

### 10.13 Risk & Fraud

| Feature | Status | Notes |
|---|---|---|
| Risk rule engine V1 | ✅ | Basic rule matching |
| Risk rule engine V2 | ✅ | 4 evaluators (velocity, pattern, IP, behavioral) |
| BLOCK / REVIEW / CHALLENGE / ALLOW decisions | ✅ | Full decision tree |
| Manual review queue | ✅ | `ManualReviewCase` entity |
| IP blocklist | ✅ | Fast lookup table |
| Velocity checks | ✅ | Time-windowed transaction counting |
| Payment pattern analysis | ✅ | Anomaly scoring |
| Merchant-level risk rules | ✅ | Per-merchant configurable rules |
| Platform-level risk rules | ✅ | Global rules in platform scope |
| Machine learning scoring | ❌ | Rule-based only |
| 3D Secure challenge flow | ❌ | Not implemented |
| Card BIN intelligence | ❌ | No BIN database integration |
| Device fingerprinting | ❌ | Not implemented |
| Consortium fraud data | ❌ | Not implemented |

### 10.14 Reconciliation

| Feature | Status | Notes |
|---|---|---|
| L1 internal ledger reconciliation | ✅ | Platform debit/credit balance check |
| L2 gateway reconciliation | ✅ | Intent vs gateway state match |
| L3 bank statement reconciliation | ✅ | Statement upload + matching |
| L4 composite settlement reconciliation | ✅ | Batch settlement matching |
| Mismatch tracking + resolution | ✅ | Full mismatch entity |
| Statement import | ✅ | CSV/file-based import |
| Settlement batch management | ✅ | Batch entity with status |
| Real-time reconciliation | ⚠️ | Triggered run, not streaming |
| Automated mismatch resolution | ❌ | Manual resolution only |
| Recon CSV/PDF export | ❌ | Not implemented |

### 10.15 Reporting & Analytics

| Feature | Status | Notes |
|---|---|---|
| Daily KPI snapshot | ✅ | Revenue, MRR, ARR, churn, growth |
| Customer billing summary | ✅ | Per-customer lifetime spend |
| Revenue waterfall | ✅ | Recognized vs deferred by period |
| Ops subscription status breakdown | ✅ | Active/paused/past_due counts |
| Ops gateway health summary | ✅ | Per-gateway up/down/degraded |
| Ops dunning effectiveness | ✅ | Recovery rate per policy |
| Ops refund analysis | ✅ | By category, gateway |
| Ops dispute analysis | ✅ | By reason code, merchant |
| Entity timeline / audit trail | ✅ | All entity events in order |
| Ad-hoc analytics / SQL queries | ❌ | Not implemented |
| Custom report builder | ❌ | Not implemented |
| Real-time dashboard streaming | ❌ | Pre-computed projections only |
| Data export (CSV, Excel) | ❌ | API only |
| BI tool integration (Looker, Tableau) | ❌ | Not implemented |

### 10.16 Platform Infrastructure

| Feature | Status | Notes |
|---|---|---|
| Idempotency (request deduplication) | ✅ | Two-tier Redis NX + DB |
| Rate limiting (sliding window) | ✅ | Lua-based Redis, graceful degradation |
| Outbox pattern (transactional events) | ✅ | DB polling with graceful DLQ |
| Dead letter queue | ✅ | With retry endpoint |
| Integrity checkers (22 checks) | ✅ | Scheduled + on-demand |
| Repair actions (7 actions) | ✅ | Triggered via admin API |
| Business fingerprint deduplication | ✅ | SHA-256 on business effect |
| Concurrency guard (optimistic locking) | ✅ | DB version column |
| Feature flags | ✅ | `ops_feature_flags` table |
| Job locking | ✅ | `ops_job_locks` table |
| Deep health check | ✅ | DB, Redis, outbox lag, DLQ count |
| Kafka / message broker | ❌ | DB-polling outbox only |
| Circuit breaker | ❌ | No Resilience4j |
| Distributed tracing | ❌ | No Jaeger / Zipkin |
| OpenTelemetry metrics | ⚠️ | Micrometer counters present; no OTEL export configured |

---

## 11. Gap Analysis & Roadmap

### 11.1 Critical Gaps (HIGH severity — blocks production use)

| # | Gap | Severity | Effort | Recommendation |
|---|---|---|---|---|
| G-01 | No production payment gateway adapter | HIGH | L | Implement Stripe / Razorpay adapter behind `PaymentGatewayPort` |
| G-02 | No message broker (Kafka/RabbitMQ) | HIGH | L | Replace DB-polling outbox with real broker for reliability + throughput |
| G-03 | No multi-currency FX conversion | HIGH | L | Add FX rate service + currency on all monetary columns |
| G-04 | No 3DS / SCA / EMV challenge | HIGH | M | Implement challenge flow in PaymentIntent state machine |
| G-05 | No metered / usage-based billing | HIGH | L | Add `usage_records` entity + billing engine aggregation |
| G-06 | No checkout UI / hosted pages | HIGH | M | Build or integrate a hosted checkout page for B2C flows |
| G-07 | SSRF vulnerability in webhook dispatcher | HIGH | S | Add URL validation; block RFC 1918 / metadata service IPs |
| G-08 | No GDPR erasure endpoint | HIGH | S | Implement `/customers/{id}/erase` with PII clear + anonymization |

### 11.2 Important Gaps (MEDIUM severity — limits scalability/adoption)

| # | Gap | Severity | Effort | Recommendation |
|---|---|---|---|---|
| G-09 | No OAuth2 / OIDC | MEDIUM | M | Add Spring Security OAuth2 Resource Server |
| G-10 | Tax India-only (no TaxJar/Avalara) | MEDIUM | L | Abstract tax calculator interface; add international adapter |
| G-11 | Rule-based risk only (no ML) | MEDIUM | L | Integrate ML scoring API (e.g., AWS Fraud Detector) |
| G-12 | No distributed tracing | MEDIUM | S | Add OpenTelemetry SDK + Jaeger/Tempo export |
| G-13 | Token blacklist no TTL purge | MEDIUM | S | Add scheduled purge of expired tokens from blacklist table |
| G-14 | No credit balance carry-forward on pause/resume | MEDIUM | M | Implement credit carry logic in `SubscriptionStateMachine` |
| G-15 | No dunning retry by error code | MEDIUM | S | Map gateway error codes to retry vs no-retry categories |
| G-16 | No refresh token rotation | MEDIUM | S | Invalidate old refresh token on each use |
| G-17 | Webhook dispatcher SSRF (no URL validation) | MEDIUM | S | Already listed as G-07 (HIGH) |
| G-18 | No webhook signature constant-time compare | MEDIUM | S | Use `MessageDigest.isEqual()` for constant-time comparison |

### 11.3 Minor Gaps (LOW severity — nice to have)

| # | Gap | Severity | Effort | Recommendation |
|---|---|---|---|---|
| G-19 | No event compaction / archival | LOW | S | Archive domain_events older than N days to cold storage |
| G-20 | No subscription billing anchor normalization | LOW | S | Normalize anchor to 28th for February consistency |
| G-21 | No per-key API key scopes / permissions | LOW | M | Add `scopes` column to `merchant_api_keys` |
| G-22 | No recon CSV export | LOW | S | Add `/recon/export-csv` endpoint |
| G-23 | No support SLA tracking | LOW | S | Add `sla_due_at` field to `support_cases` |
| G-24 | No merchant webhook log UI | LOW | S | Build webhook delivery log fetch (endpoint exists, no UI) |
| G-25 | No event sourcing | LOW | L | Current system rebuilds state from DB; event replay is for audit only |

---

## 12. Fintech Platform Benchmark

### 12.1 Comparison Matrix

| Feature Area | This Platform | Stripe | Razorpay | Chargebee | JusPay |
|---|---|---|---|---|---|
| **Core billing stack** | ✅ Complete | ✅ | ✅ | ✅ | ⚠️ Partial |
| **Production payment gateway** | ❌ Simulated only | ✅ Native | ✅ Native India | ✅ Via integrations | ✅ India focus |
| **Multi-currency** | ❌ | ✅ 135+ currencies | ✅ INR + USD | ✅ Multi-currency | ✅ INR focus |
| **Metered / usage billing** | ❌ | ✅ | ❌ | ✅ | ❌ |
| **Tiered / volume pricing** | ❌ | ✅ | ❌ | ✅ | ❌ |
| **3DS / SCA / EMV** | ❌ | ✅ Stripe.js + 3DS2 | ✅ | Via gateway | ✅ |
| **ML risk / fraud scoring** | ❌ Rule-based | ✅ Stripe Radar (ML) | ✅ Fraud detection | ❌ | ✅ |
| **Double-entry ledger** | ✅ Full COA | ✅ Stripe Treasury | ❌ | ✅ | ❌ |
| **Revenue recognition (ASC 606)** | ✅ | ✅ Stripe Revenue Recognition | ❌ | ✅ | ❌ |
| **India GST** | ✅ Full CGST/SGST/IGST | ❌ | ✅ | ✅ | ✅ |
| **Outbox / reliability pattern** | ✅ | ✅ Internal | ❌ (webhook retries only) | ❌ | ❌ |
| **Integrity checkers (automated)** | ✅ 22 checks | ❌ External monitoring | ❌ | ❌ | ❌ |
| **Reconciliation (4-layer)** | ✅ | ✅ Stripe Sigma | ❌ | ❌ | ✅ |
| **Rate limiting (API)** | ✅ Sliding window | ✅ | ✅ | ✅ | ✅ |
| **Idempotency (formal)** | ✅ Two-tier | ✅ | ✅ | ⚠️ | ⚠️ |
| **Dunning (configurable)** | ✅ V2 JSON policy | ✅ Smart Retries | ✅ | ✅ | ⚠️ |
| **Dispute management** | ✅ | ✅ Stripe Disputes | ✅ | ❌ | ✅ |
| **Webhook system** | ✅ HMAC-SHA256 | ✅ | ✅ | ✅ | ✅ |
| **Multi-tenant (merchant model)** | ✅ Full | ✅ Connect | ✅ Route Money | ✅ | ✅ |
| **Subscription state machine** | ✅ 8-state | ✅ | ✅ | ✅ | ❌ |
| **Test coverage** | ✅ 1374 tests | N/A | N/A | N/A | N/A |
| **Open source / self-hostable** | ✅ | ❌ SaaS | ❌ SaaS | ❌ SaaS | ❌ SaaS |

### 12.2 Architectural Maturity Highlights

**Where this platform exceeds SaaS competitors:**
- **22 integrity checkers** — most SaaS platforms delegate consistency monitoring to external observability tools. This codebase has native, automated, domain-aware consistency validation as a first-class feature.
- **4-layer reconciliation** — full L1-L4 with bank statement import and settlement batch matching is typically only available in enterprise-tier fintech backends.
- **ASC 606 revenue recognition** — built-in deferred revenue tracking with catch-up runs is enterprise-grade and absent from Razorpay / JusPay.
- **Full double-entry ledger** — chart of accounts, balanced entry validation, 8 journal templates — matches Stripe Treasury's capability.
- **Outbox pattern with DLQ** — reliable event delivery as architectural pattern, not just webhook retry.

**Where this platform lags:**
- The most critical production gap is the **SimulatedPaymentGateway** — it means no real money movement. A Stripe or Razorpay adapter needs to be the first engineering investment.
- Metered billing and multi-currency are fundamental for B2B SaaS expansion.


---

## 13. Operational Runbook

### 13.1 Local Development Setup

```bash
# Prerequisites: Java 17+, Maven 3.8+, Docker (for Testcontainers), Redis 7

# Clone and build
git clone <repo-url>
cd membership-program
mvn clean package -DskipTests

# Start Redis (required for rate limiting, idempotency, routing cache)
docker run -d --name redis -p 6379:6379 redis:7-alpine

# Start PostgreSQL (or use Testcontainers for tests only)
docker run -d --name postgres \
  -e POSTGRES_DB=membership_db \
  -e POSTGRES_USER=membership_user \
  -e POSTGRES_PASSWORD=membership_pass \
  -p 5432:5432 postgres:16-alpine

# Configure application.properties
# spring.datasource.url=jdbc:postgresql://localhost:5432/membership_db
# spring.datasource.username=membership_user
# spring.datasource.password=membership_pass
# spring.redis.host=localhost
# spring.redis.port=6379
# app.encryption.key=<32-byte-base64-key>

# Run the application
mvn spring-boot:run

# Access Swagger UI
open http://localhost:8080/swagger-ui.html
```

### 13.2 Running Tests

```bash
# Full test suite (requires Docker for Testcontainers)
mvn test

# Run specific test class
mvn test -Dtest=LedgerServiceImplTest

# Run tests with coverage (if JaCoCo configured)
mvn verify

# Run Python integration tests (requires running server on :8080)
python3 master_api_tests.py

# Run stress tests
python3 stress_test.py
python3 user_stress_test.py
```

### 13.3 Database Operations

```bash
# Check Flyway migration status (via Spring Boot Actuator)
curl http://localhost:8080/actuator/flywaymigrations

# View Flyway migration history in DB
psql -U membership_user -d membership_db -c \
  "SELECT version, description, installed_on, success FROM flyway_schema_history ORDER BY installed_rank;"

# Check table counts (quick health signal)
psql -U membership_user -d membership_db -c \
  "SELECT schemaname, tablename, n_live_tup FROM pg_stat_user_tables ORDER BY n_live_tup DESC;"
```

### 13.4 Outbox & DLQ Operations

```bash
# Check outbox backlog (unprocessed events)
psql -U membership_user -d membership_db -c \
  "SELECT event_type, COUNT(*), MAX(created_at) FROM outbox_events 
   WHERE status = 'PENDING' GROUP BY event_type ORDER BY COUNT(*) DESC;"

# Check for stuck events (older than 1 hour, not in DLQ)
psql -U membership_user -d membership_db -c \
  "SELECT id, event_type, attempts, created_at, last_attempted_at 
   FROM outbox_events 
   WHERE status = 'PENDING' AND last_attempted_at < NOW() - INTERVAL '1 hour'
   ORDER BY created_at;"

# View Dead Letter Queue via API
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/dlq

# Retry specific DLQ entry
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/dlq/{entryId}/retry

# Count DLQ entries by event type
psql -U membership_user -d membership_db -c \
  "SELECT event_type, COUNT(*) FROM outbox_dlq GROUP BY event_type;"
```

### 13.5 Integrity Checks

```bash
# Trigger a full integrity check run via API
curl -X POST \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  http://localhost:8080/admin/integrity/run

# List recent integrity runs
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/admin/integrity/runs

# Get findings for a run
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/admin/integrity/runs/{runId}/findings

# Useful integrity queries by checker category:
# Billing: invoices in PENDING with no payment intent
psql -U membership_user -d membership_db -c \
  "SELECT i.id, i.merchant_id, i.total_amount, i.status, i.created_at
   FROM invoices i LEFT JOIN payment_intents pi ON pi.invoice_id = i.id
   WHERE i.status = 'PENDING' AND pi.id IS NULL;"

# Ledger: unbalanced entries
psql -U membership_user -d membership_db -c \
  "SELECT le.id, le.reference_id, SUM(CASE WHEN ll.entry_type='DEBIT' THEN ll.amount ELSE -ll.amount END) AS imbalance
   FROM ledger_entries le JOIN ledger_lines ll ON ll.ledger_entry_id = le.id
   GROUP BY le.id HAVING ABS(SUM(CASE WHEN ll.entry_type='DEBIT' THEN ll.amount ELSE -ll.amount END)) > 0.001;"
```

### 13.6 Repair Actions

```bash
# Available repair actions:
# - InvoiceRecompute
# - LedgerSnapshotRebuild
# - OutboxEventRetry
# - ProjectionRebuild
# - ReconRun
# - RevenueScheduleRegenerate
# - WebhookDeliveryRetry

# Execute a repair action
curl -X POST \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"params": {"merchant_id": "merchant-uuid", "from_date": "2024-01-01"}}' \
  http://localhost:8080/admin/repair/InvoiceRecompute

# Rebuild all reporting projections
curl -X POST \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  http://localhost:8080/admin/repair/ProjectionRebuild
```

### 13.7 Reconciliation Operations

```bash
# Run reconciliation for a specific date
curl -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Api-Key: $MERCHANT_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"date": "2024-01-15", "gatewayId": "gateway-uuid"}' \
  http://localhost:8080/merchants/{mId}/recon/run

# List mismatches
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/merchants/{mId}/recon/mismatches?status=OPEN"

# Import bank statement
curl -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@statement.csv" \
  http://localhost:8080/merchants/{mId}/statement-imports
```

### 13.8 Redis Health & Cache

```bash
# Check Redis connectivity
redis-cli -h localhost -p 6379 ping

# Check rate limiter keys
redis-cli -h localhost -p 6379 keys "rl:*" | head -20

# Check idempotency keys
redis-cli -h localhost -p 6379 keys "idem:*" | head -20

# Check routing cache
redis-cli -h localhost -p 6379 keys "routing:*" | head -20

# Deep health check (includes Redis status)
curl http://localhost:8080/actuator/health/deep
```

### 13.9 Platform Health Monitoring

```bash
# Standard Spring Boot health
curl http://localhost:8080/actuator/health

# Deep health (DB, Redis, outbox lag, DLQ count)
curl http://localhost:8080/actuator/health/deep

# Metrics endpoint (Prometheus-compatible)
curl http://localhost:8080/actuator/metrics

# Key custom metrics to monitor:
# - ledger_unbalanced_total (should be 0)
# - outbox_pending_count (should be low)
# - dlq_entry_count (should be 0)
# - rate_limit_exceeded_total (spike = abuse signal)
# - integrity_violations_total (should be 0)

# Check feature flags
psql -U membership_user -d membership_db -c \
  "SELECT flag_name, enabled, updated_at FROM ops_feature_flags ORDER BY flag_name;"

# Check job locks (detect stuck jobs)
psql -U membership_user -d membership_db -c \
  "SELECT job_name, locked_by, locked_at, expires_at FROM ops_job_locks WHERE expires_at > NOW();"
```

---

## 14. Compliance Posture

### 14.1 PCI DSS

| Requirement | Status | Evidence |
|---|---|---|
| No raw PAN storage | ✅ | `provider_token` only; no `card_number` column |
| Encryption at rest (sensitive data) | ✅ | AES-256-GCM for PII fields |
| Encryption key management | ⚠️ | Static application property; no KMS |
| Access control to cardholder data | ⚠️ | RBAC present; no formal PCI-scoped access review |
| Network segmentation | ⚠️ | Not configured in application; assumed at infra level |
| Audit log of PCI-scoped operations | ⚠️ | `audit_log` table exists; not confirmed for all PCI events |
| Vulnerability scan / ASV | ❌ | No automated CVE scanning in pipeline |
| Penetration testing | ❌ | Not documented |
| Formal PCI-DSS SAQ / ROC | ❌ | Not in codebase scope |

**Assessment**: This codebase avoids raw PAN storage (critical PCI requirement) through provider tokenization. However, a formal PCI DSS attestation would require KMS-managed keys, network segmentation evidence, and a third-party penetration test.

### 14.2 GDPR / Data Privacy

| Requirement | Status | Evidence |
|---|---|---|
| Data minimization | ✅ | Only necessary PII fields stored |
| Encryption of personal data | ✅ | Phone, billing_address, shipping_address: AES-256-GCM |
| Right to access | ⚠️ | `GET /customers/{id}` provides data; no formal export endpoint |
| Right to erasure (Article 17) | ❌ | No `/customers/{id}/erase` or anonymization endpoint |
| Consent management | ❌ | No consent tracking entity |
| Data retention policies | ❌ | No automated purge or retention schedule |
| Data breach notification | ❌ | No incident response mechanism in codebase |
| DPA / processing records | ❌ | Not in codebase scope |
| Cross-border transfer controls | ❌ | Not implemented |

**Assessment**: The platform stores and encrypts PII appropriately but lacks GDPR-required erasure capability and consent management. For EU customer processing, implementing the erasure endpoint (G-08) is a legal obligation.

### 14.3 India IT Act & DPDP Bill

| Requirement | Status | Evidence |
|---|---|---|
| Data localization | ⚠️ | Not enforced at application level; depends on deployment region |
| Encryption of sensitive personal data | ✅ | AES-256-GCM |
| Audit trail | ✅ | `audit_log` + `domain_events` + entity timeline |
| IT Act Section 43A (reasonable security) | ✅ | BCrypt, AES-256-GCM, RBAC satisfy "reasonable practices" |
| DPDP Bill consent requirement | ❌ | No consent management |
| DPDP Bill data principal rights | ❌ | No erase / correction workflow |
| Grievance redressal mechanism | ⚠️ | Support cases exist; no formal DPO assignment |

**Assessment**: Current implementation likely satisfies IT Act Section 43A "reasonable security practices." DPDP Bill compliance (if enacted) would require consent management and data principal rights — significant work.

### 14.4 India GST / Tax

| Requirement | Status | Evidence |
|---|---|---|
| CGST / SGST calculation | ✅ | Intrastate transaction tax via `GstCalculatorService` |
| IGST calculation | ✅ | Interstate transaction tax |
| GSTIN storage (merchant + customer) | ✅ | `gstin` column on both entities |
| Tax-exempt customer handling | ✅ | `is_exempt` flag |
| Tax invoice line items | ✅ | `INDIA_GST_CGST` / `SGST` / `IGST` line types |
| Reverse charge mechanism | ⚠️ | Column present; service logic not confirmed fully wired |
| Composition scheme handling | ❌ | Not implemented |
| E-invoice generation (GST portal API) | ❌ | Not implemented |
| GSTR-1 / GSTR-3B filing support | ❌ | Not implemented |
| TDS / TCS on platform fees | ❌ | Not implemented |
| HSN / SAC code management | ⚠️ | Tax profiles exist; HSN/SAC not explicitly surfaced |

**Assessment**: Full CGST/SGST/IGST calculation is implemented and production-ready for Indian merchants. E-invoicing (mandatory for businesses > ₹5 crore turnover) and filing support would be required for full statutory compliance.

---

## 15. Quick Reference Glossary

| Term | Definition |
|---|---|
| COA | Chart of Accounts — the hierarchical list of ledger accounts |
| DLQ | Dead Letter Queue — failed outbox events after `MAX_ATTEMPTS=5` |
| FX | Foreign Exchange — currency conversion (not implemented) |
| IGST | Integrated GST — applied on interstate India transactions |
| CGST | Central GST — 50% of intrastate GST |
| SGST | State GST — 50% of intrastate GST |
| OCC | Optimistic Concurrency Control — DB version column |
| PAN | Primary Account Number — raw card number (never stored) |
| PII | Personally Identifiable Information |
| SCA | Strong Customer Authentication — EU PSD2 requirement |
| 3DS2 | 3D Secure v2 — EMV challenge flow for card payments |
| ASC 606 | US GAAP revenue recognition standard |
| IFRS 15 | International equivalent of ASC 606 |
| Outbox | Transactional outbox pattern — events in same DB transaction |
| HMAC | Hash-based Message Authentication Code |
| GCM | Galois/Counter Mode — AES authenticated encryption mode |
| DPO | Data Protection Officer — GDPR/DPDP requirement |
| DPDP | Digital Personal Data Protection Bill (India, 2023) |
| Testcontainers | Docker-based real-DB testing library for JUnit |

---

## Appendix: File Index

### Key Source Files

| File | Purpose |
|---|---|
| `MembershipApplication.java` | Spring Boot entry point |
| `DatabaseConfig.java` | DataSource, JPA config, `EncryptionKeyProvider` |
| `SwaggerConfig.java` | OpenAPI / Springdoc configuration |
| `GlobalExceptionHandler.java` | Centralized REST error responses |
| `MembershipException.java` | Domain exception with `ErrorCode` enum |

### Core Domain Services

| Service | Package | Purpose |
|---|---|---|
| `LedgerServiceImpl` | `ledger` | Double-entry journal entry creation |
| `InvoiceTotalService` | `billing` | Grand total calculation across all line types |
| `ProrationCalculator` | `billing` | Exact-days proration for subscription changes |
| `GstCalculatorService` | `billing.tax` | CGST / SGST / IGST routing and calculation |
| `SubscriptionStateMachine` | `subscription` | 8-state machine with guard conditions |
| `PaymentRoutingServiceImpl` | `payment.routing` | Rule evaluation + Redis cache + health-aware selection |
| `RiskDecisionService` | `risk` | BLOCK/REVIEW/CHALLENGE/ALLOW pipeline |
| `OutboxProcessingService` | `outbox` | SKIP LOCKED polling + backoff + DLQ promotion |
| `IdempotencyFilter` | `platform` | Redis NX + DB two-tier idempotency |
| `RedisSlidingWindowRateLimiter` | `platform` | Lua ZREMRANGEBYSCORE + ZADD rate limiter |
| `IntegrityCheckOrchestrator` | `platform` | Runs 22 checkers, stores findings |
| `RepairActionExecutor` | `platform` | Executes 7 repair actions by key |
| `RevenueRecognitionService` | `ledger.revenue` | ASC 606 schedule + catch-up + waterfall |
| `ReconciliationService` | `recon` | L1–L4 reconciliation pipeline |

---

*PROJECT_ANALYSIS.md — Generated analysis of the FirstClub Membership Platform*  
*Codebase statistics: 901 Java source files · 49 Flyway migrations · ~90 database tables · 1374 passing tests*  
*Build status: BUILD SUCCESS (mvn test, 0 failures)*  
*Last updated: June 2025*
