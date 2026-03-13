# PROJECT_ANALYSIS.md

> **Document type:** Internal Engineering Architecture Review
> **Last updated:** 2026-03-13
> **Workspace:** membership-program (Spring Boot 3.4.3 · Java 17 · PostgreSQL)
> **Author note:** Based entirely on direct static analysis of the repository — source code,
> migrations, configuration, and tests. No runtime trace or live traffic analysis was performed.
> Sections are flagged explicitly where a feature is absent, partial, or scaffolded only.

---

## 1. Executive Summary

FirstClub Membership Program is a **modular-monolith fintech backend** implementing the
subscription-billing-payments stack for a multi-merchant SaaS platform. The system sits above
the MVP tier in architectural maturity: it contains double-entry ledger accounting, transactional
outbox, idempotency controls, distributed locking with fencing tokens, policy-driven dunning,
webhook delivery, a risk engine with manual review, a reconciliation pipeline, and revenue
recognition. All 971 production Java files are organized into 21 clearly bounded domain packages
under `com.firstclub.*`, backed by 69 Flyway migrations creating 94 database tables.

**Strongest design choices:**
- Append-only domain event log with replay safety (`domain_events`, `events.*` package).
- Database-enforced ledger immutability via PostgreSQL trigger (`trg_ledger_entries_immutable`,
  `trg_ledger_lines_immutable` in V56).
- Payment capacity invariants encoded both in application service
  (`PaymentCapacityInvariantService`) and as DB `CHECK` constraints (V55).
- Transactional outbox with lease-based heartbeat, aggregate ordering, and DLQ fallback
  (`outbox.*` package, V11, V47, V58).
- Redis-accelerated idempotency with DB source-of-truth fallback
  (`platform.idempotency.*`, V35, V51).
- Row-Level Security on the four highest-sensitivity tables (V67), enforced via
  `RlsTenantContextConfigurer` using `SET LOCAL`.
- Lua-script-safe distributed locks with fencing-token audit trail (`platform.lock.*`, V52).

**Weakest areas:**
- Risk context in the payment-confirm path passes null IP/device, eliminating the practical
  value of IP-velocity and device-reuse evaluators at the most critical payment gate.
- RLS is enabled on only four tables; the majority of tenant-scoped tables rely on
  application-layer merchant ownership checks which are manually maintained per service method.
- Statement import stores aggregate CSV totals only — no line-level persistence — preventing
  exact-match forensic reconciliation.
- Projection lag monitoring uses oldest-update watermark instead of newest-update watermark,
  producing inflated staleness signals.
- The monolith runs as a single JVM process; there is no horizontal pod isolation for background
  scheduler workers, creating scale and noisy-neighbor risks.

**Production readiness level:**
Suitable for controlled production deployment with disciplined operational teams at moderate
scale (sub-100 TPS peak). Not ready for >1K TPS sustained workloads or regulatory multi-region
deployments without the hardening described in this document.

**Overall backend maturity:** ★★★★☆ — Late-stage fintech prototype / early production.

---

## 2. Repository Overview

### 2.1 Top-Level Structure

```
membership-program/
├── .github/workflows/build.yml      # GitHub Actions CI: build + test on push/PR to main/develop
├── docs/                            # 83 documentation files (architecture, operations, testing)
├── load-tests/k6/                   # 5 k6.io load test scripts
├── scripts/                         # Developer setup helper scripts
├── src/
│   ├── main/
│   │   ├── java/com/firstclub/      # 971 production Java source files (21 domain packages)
│   │   └── resources/
│   │       ├── application.properties          # Base configuration
│   │       ├── application-dev.properties      # H2, devtools, relaxed security
│   │       ├── application-local.properties    # Local PostgreSQL override
│   │       ├── application-prod.properties     # PostgreSQL, env-var secrets
│   │       ├── logback-spring.xml              # Structured JSON logging (Logstash encoder)
│   │       └── db/migration/                  # 69 Flyway SQL migrations (V1–V69)
│   └── test/
│       └── java/com/firstclub/      # 204 test files
├── Dockerfile                       # Multi-stage eclipse-temurin:17 build → non-root runtime
├── docker-compose.yml               # Local dev: PostgreSQL 16 + pgAdmin
├── pom.xml                          # Maven build (Spring Boot 3.4.3 parent)
└── PROJECT_ANALYSIS.md              # This document
```

### 2.2 Build System

- **Build tool:** Apache Maven 3.x with `spring-boot-starter-parent:3.4.3`.
- **Java version:** 17 (LTS), compiled via `maven-compiler-plugin`.
- **Packaging:** Single fat JAR via `spring-boot-maven-plugin`.
- **Test runner:** `maven-surefire-plugin` matching `*Test.java`, `*Tests.java`, `*IT.java`.
- **CI:** GitHub Actions `.github/workflows/build.yml` — runs `mvn clean verify -B` on every
  push/PR to `main` and `develop`; uploads Surefire reports as artifacts on failure.
- **Dependency highlights:**
  - `spring-boot-starter-{web,data-jpa,security,cache,data-redis,actuator,validation}`
  - `postgresql` (runtime), `h2` (dev/test)
  - `flyway-core` — Flyway migrations
  - `lombok:1.18.30`, `mapstruct:1.5.5.Final`
  - `jjwt-api/impl/jackson:0.12.5`
  - `caffeine` — in-process cache for plans, tiers, analytics
  - `micrometer-registry-prometheus`
  - `logstash-logback-encoder`
  - `testcontainers` (PostgreSQL, JUnit 5)

### 2.3 Module Organization

All code lives under a single Maven module (`com.firstclub:membership-program:1.0.0`).
There are no Maven sub-modules; the 21 domain packages are logical (package-level)
boundaries, not deployment units.

| Domain Package | File Count | Responsibility |
|---|---:|---|
| `platform` | 217 | Cross-cutting: locks, Redis, idempotency, rate limit, dedup, integrity, repair, RLS, scheduler, observability |
| `payments` | 138 | Payment intents v2, attempts, routing, refunds, disputes, recovery, gateway emulator |
| `billing` | 61 | Invoices, credit notes, discounts, tax, revenue recognition (schedule + waterfall) |
| `reporting` | 60 | Projections, snapshots, ops timeline, KPI dashboards |
| `ledger` | 51 | Double-entry chart of accounts, immutable journal entries, reversal service |
| `merchant` | 44 | Merchant accounts, settings, API keys, modes, versioning |
| `risk` | 44 | Risk rules engine, manual review queue, score decay, explainability |
| `recon` | 43 | Reconciliation reports, settlement batches, statement import, mismatch lifecycle |
| `dunning` | 34 | Policy-driven retry scheduling, failure classification, backup payment methods |
| `catalog` | 29 | Products, prices, price versions (merchant-scoped) |
| `events` | 23 | Append-only domain event log, replay service, schema versioning |
| `subscription` | 21 | Subscription v2 lifecycle, schedules, state machine |
| `customer` | 20 | Merchant-scoped customer model, PII encryption |
| `outbox` | 19 | Transactional outbox publisher/poller, DLQ, lease heartbeat, ordering |
| `notifications` | 18 | Webhook endpoints, HMAC delivery, retry, auto-disable |
| `membership` | 83* | Legacy user/plan/tier/subscription v1 model + JWT auth |
| `audit` | 8 | `FinancialAuditAspect`, audit entries, financial operation annotation |
| `admin` | 5 | Cross-domain search with Redis cache |
| `support` | 18 | Support cases and notes |
| `integrity` | 31 | Invariant checker runs, findings, repair dispatcher |
| `ops` | 4 | Operational utilities, startup bootstrap |

*`membership` is the legacy package retaining the original user authentication and v1
subscription data model alongside the newer merchant-facing domain.


---

## 3. Architecture Overview

### 3.1 Architectural Style

The system is a **modular monolith** with layered package structure. All domains share a single
Spring application context, a single HikariCP connection pool against one PostgreSQL instance,
and optionally one Redis server. There is no message broker (Kafka, RabbitMQ) and no separate
microservice processes.

Cross-domain decoupling is achieved via:
1. The transactional outbox (`outbox_events` table) for reliable async dispatch within the
   same JVM.
2. The append-only `domain_events` table for audit, replay, and projection fan-out.
3. Merchant webhooks (`merchant_webhook_deliveries`) for external integration callbacks.

### 3.2 Layered Design

Each domain follows the same four-layer convention:

```
HTTP Layer         ─ @RestController (com.firstclub.*.controller)
Application Layer  ─ @Service (com.firstclub.*.service)
Repository Layer   ─ @Repository / Spring Data JPA (com.firstclub.*.repository)
Persistence Layer  ─ @Entity (com.firstclub.*.entity) + Flyway-managed PostgreSQL schema
```

Cross-cutting concerns (idempotency, rate limiting, observability, locking) live in
`com.firstclub.platform.*` and are injected into domain services.

### 3.3 High-Level Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          HTTP Clients / Merchants                           │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │  REST over HTTPS
┌──────────────────────────────────▼──────────────────────────────────────────┐
│                          Spring Security Filter Chain                       │
│  JwtAuthenticationFilter → RateLimitInterceptor → IdempotencyFilter         │
│  RequestIdFilter → CorrelationIdFilter → RequestLoggingFilter               │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼──────────────────────────────────────────┐
│                            REST Controllers                                 │
│  PaymentIntentV2Controller  · BillingController  · SubscriptionController   │
│  LedgerController  · ReconAdminController  · RiskAdminController  · …(50+) │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │  @Transactional service calls
┌──────────────────────────────────▼──────────────────────────────────────────┐
│                           Application Services                              │
│  PaymentIntentService  · InvoiceService  · DunningServiceV2                │
│  LedgerService  · ReconciliationService  · RiskDecisionService  · …(150+)  │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │  JPA / Repository calls
┌──────────────────────────────────▼──────────────────────────────────────────┐
│             JPA Repositories  (Spring Data, 70+ interfaces)                 │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼──────────────────────────────────────────┐
│    HikariCP Connection Pool (20 max prod, 10 max dev) → PostgreSQL 16       │
└─────────────────────────────────────────────────────────────────────────────┘

                  ┌────────────────────────────────────────┐
                  │         Async Background Workers        │
                  │                                        │
                  │  OutboxPoller ──► OutboxEventHandlers  │
                  │  DunningSchedulerV2                    │
                  │  NightlyReconScheduler                 │
                  │  RevenueRecognitionScheduler           │
                  │  ProjectionSnapshotScheduler           │
                  │  WebhookDeliveryRetryScheduler         │
                  │  RiskMaintenanceScheduler              │
                  │  IdempotencyCleanupScheduler           │
                  └──────────────┬─────────────────────────┘
                                 │  PostgreSQL advisory locks
                                 │  (SchedulerLockService)
                                 ▼
                           PostgreSQL 16

        ┌─────────────────────────────────────┐
        │           Redis (Optional)          │
        │  Idempotency response cache         │
        │  Sliding-window rate limiter        │
        │  Distributed lock (Lua scripts)     │
        │  Fencing token INCR                 │
        │  Projection / timeline cache        │
        │  Routing decision cache             │
        │  Search cache                       │
        └─────────────────────────────────────┘
```

### 3.4 Module Interaction Model

```
subscription ──► billing (invoice creation)
billing      ──► payments (intent create/confirm)
payments     ──► ledger (capture/refund/dispute accounting)
payments     ──► risk (pre-confirm evaluation)
billing      ──► ledger (revenue schedule generation)
dunning      ──► payments (retry confirm via gateway port)
dunning      ──► subscription (activate / suspend / cancel)
outbox       ──► all domains (async handler dispatch)
events       ──► reporting (projection updates via listener)
recon        ──► payments / ledger (mismatch detection)
platform     ──► all domains (idempotency, locks, integrity, RLS)
```

### 3.5 Request Lifecycle

```
1. HTTPS request arrives at Spring MVC DispatcherServlet
2. JwtAuthenticationFilter validates Bearer token → sets SecurityContext
3. RateLimitInterceptor checks Redis sliding window (or DB fallback)
4. IdempotencyFilter checks idempotency_keys table + Redis cache
   → if COMPLETED: return cached response (short-circuit)
   → if PROCESSING: return 409 (in-flight conflict)
   → if NEW: mark PROCESSING, proceed
5. Controller validates @Valid request DTO
6. Service executes business logic inside @Transactional
   a. Merchant ownership / customer ownership validation
   b. State machine guard (StateMachineValidator)
   c. Business rule checks (capacity, invariants)
   d. JPA entity persist / update
   e. outbox.publish(eventType, payload) — atomic with business write
   f. domain_events append
7. IdempotencyFilter stores response in idempotency_keys → COMPLETED
8. Structured JSON response emitted; MDC fields flushed to log
9. RequestLoggingFilter records latency to MDC
```


---

## 4. Runtime Flow

### 4.1 Inbound Payment Confirm (Critical Path)

```
POST /api/v2/payment-intents/{id}/confirm
│
├─ JwtAuthenticationFilter       (validates HS256 JWT, JJWT 0.12.5)
├─ RateLimitInterceptor          (PAYMENT_CONFIRM policy via @RateLimit annotation)
├─ IdempotencyFilter             (endpoint+body fingerprint, idempotency_keys table)
│
├─ PaymentIntentV2Controller.confirm()
│   └─ @Valid PaymentIntentConfirmRequestDTO
│
├─ PaymentIntentService.confirm()  [@Transactional]
│   ├─ Load PaymentIntentV2 (pessimistic lock: SELECT … FOR UPDATE)
│   ├─ Guard: SUCCEEDED → idempotent return
│   ├─ Guard: CANCELLED → throw terminal-state exception
│   ├─ Guard: FAILED → check last attempt retriable flag
│   ├─ RiskDecisionService.evaluate(RiskContext) — NOTE: IP/device=null currently
│   │   └─ RiskRuleService.evaluate() → pluggable RuleEvaluator list
│   │       ├─ BlocklistIpEvaluator
│   │       ├─ IpVelocityEvaluator
│   │       ├─ DeviceReuseEvaluator
│   │       └─ UserVelocityEvaluator
│   ├─ PaymentRoutingService.selectGateway()
│   │   └─ GatewayRouteRule evaluation → routing_snapshot saved to payment_attempts
│   ├─ PaymentAttemptService.createAttempt()
│   ├─ GatewayPort.charge() (simulated in dev; real gateway in prod)
│   ├─ On SUCCESS:
│   │   ├─ PaymentIntentV2 → SUCCEEDED
│   │   ├─ PaymentAttemptService.markSucceeded()
│   │   ├─ InvoiceService.markPaid(invoiceId)
│   │   ├─ SubscriptionService.activate(subscriptionId)
│   │   ├─ LedgerService.postCapture(...)
│   │   ├─ RevenueScheduleService.generate(...)  [non-blocking, caught]
│   │   └─ outbox.publish(PAYMENT_SUCCEEDED, payload)
│   └─ On FAILURE:
│       ├─ PaymentAttemptService.markFailed(failureCategory, retriable)
│       └─ outbox.publish(PAYMENT_FAILED, payload)
│
├─ domain_events.append(PAYMENT_SUCCEEDED / PAYMENT_FAILED)
└─ IdempotencyFilter stores 200/4xx response in idempotency_keys
```

### 4.2 Outbox Dispatch Path

```
OutboxPoller (@Scheduled, fixed-rate) — protected by PostgreSQL advisory lock
│
├─ leaseRecoveryService.recoverStaleLeases()   (lease_expires_at < NOW)
├─ outboxEventRepository.lockDueEvents(batchSize)
│   └─ SELECT … FOR UPDATE SKIP LOCKED WHERE status='NEW' AND next_attempt_at<=NOW
│
└─ for each event:
    └─ outboxService.processSingleEvent(id)  [@Transactional REQUIRES_NEW]
        ├─ OutboxEventHandlerRegistry.resolve(eventType) → OutboxEventHandler
        ├─ handler.handle(event)
        │   └─ e.g. ProjectionUpdateHandler, WebhookDispatchHandler, …
        ├─ On SUCCESS: status=DONE
        ├─ On FAILURE (attempts < MAX_ATTEMPTS):
        │   └─ backoff schedule: [5, 15, 30, 60] minutes → status=NEW
        └─ On FAILURE (attempts >= 5):
            ├─ status=FAILED
            └─ DeadLetterMessage INSERT into dead_letter_messages
```

### 4.3 Scheduler Concurrency Model

All scheduled jobs (`@Scheduled`) acquire a PostgreSQL advisory lock via
`SchedulerLockService` before doing any work. The lock key is derived from
`pg_try_advisory_xact_lock(hashCode(jobName))`. If the lock is not acquired (another
JVM instance holds it), the job silently skips the run. Execution outcomes are recorded
in `scheduler_execution_history` (introduced in V53).

`PrimaryOnlySchedulerGuard` additionally queries `DatabaseRoleChecker` to ensure that
schedulers only fire on the PostgreSQL primary node, preventing accidental scheduler
execution on read replicas during failover transitions.

### 4.4 Error Response Model

Domain exceptions extend `BaseDomainException` (`platform.errors`). The global exception
handler maps them to structured HTTP responses:

```json
{
  "errorCode": "PAYMENT_CAPACITY_EXCEEDED",
  "message": "Refunded + disputed amount would exceed captured amount",
  "httpStatus": 422
}
```

Notable exception types:
- `IdempotencyConflictException` → 409
- `RequestInFlightException` → 409
- `InvariantViolationException` → 422
- `StaleOperationException` → 409
- `RateLimitExceededException` → 429
- `RiskViolationException` → 403


---

## 5. Domain Modules Deep Analysis

### 5.1 `membership` — Legacy User Authentication and V1 Subscription Model

**Ownership:** Original user-facing auth and v1 membership/plan model.

**Core classes:**
- `SecurityConfig` — Spring Security configuration. Stateless JWT, BCrypt(12), CSRF disabled.
- `JwtAuthenticationFilter` — reads `Authorization: Bearer <token>`, validates via JJWT,
  loads `UserDetailsService`. In-memory token blacklist via `membership.filter` package.
- `MembershipController`, `PlanController`, `TierController` — public-read endpoints for plans
  and tiers; authenticated endpoints for subscriptions.
- Entities: `User`, `MembershipTier`, `MembershipPlan`, `Subscription` (v1).

**Domain model quality:**
- `users`, `membership_tiers`, `membership_plans`, `subscriptions` — all created in V1.
- Strong index coverage for query patterns (`idx_sub_user_status_end`,
  `idx_sub_auto_renewal_next_bill`).
- Caffeine in-process cache for plans and tiers (`spring.cache.cache-names=plans,plansByTier`).

**Critical observation:** The v1 `Subscription` entity (table `subscriptions`) coexists with
v2 `SubscriptionV2` (`subscriptions_v2`). The v1 model does not have merchant scoping.
The system has **two parallel subscription lifecycles** — v1 (user-centric) and v2
(merchant-customer-product-price model) — which creates potential semantic confusion in
cross-domain flows.

**Layering violations:** None observed; clean vertical slice.

**Coupling:** High coupling to JWT/Spring Security plumbing; minimal coupling to v2 domains.

---

### 5.2 `payments` — Payment Intent, Attempts, Routing, Refunds, Disputes

**Ownership:** Full payment orchestration lifecycle.

**Core classes:**
- `PaymentIntentV2Controller` — CRUD + confirm + reconcile endpoints.
- `PaymentIntentService` / `PaymentIntentServiceImpl` — orchestrates the confirm flow including
  risk, routing, attempt creation, gateway call, accounting side-effects.
- `PaymentAttemptService` — exactly-one-success-attempt invariant enforcement.
- `PaymentRoutingService` / `PaymentRoutingServiceImpl` — selects gateway per
  `gateway_route_rules`, persists routing snapshot to `payment_attempts`.
- `PaymentCapacityInvariantService` — validates captured ≥ refunded + disputed before
  mutations, syncs minor-unit columns.
- `RefundService` / `RefundServiceImpl` — idempotent refund creation with capacity check,
  ledger reversal.
- `DisputeServiceImpl` — dispute open/resolve with reserve accounting, uniqueness per payment.
- `WebhookController` — inbound payment gateway callbacks; HMAC verification is
  **present in the controller security config** (permitAll for `/api/v1/webhooks/**`)
  but actual HMAC re-verification on the incoming payload uses
  `payments.webhook.secret` property and is implemented in `WebhookController`.
- `GatewayController` — fake gateway emulator for development; secured via `permitAll` —
  **this endpoint should be removed or auth-gated before any external-facing deployment**.

**Payment intent status machine:**
```
REQUIRES_PAYMENT_METHOD → REQUIRES_CONFIRMATION
REQUIRES_CONFIRMATION   → PROCESSING → SUCCEEDED
                                    └→ FAILED (retriable=true|false)
                                    └→ REQUIRES_ACTION (SCA/3DS)
SUCCEEDED               → terminal
CANCELLED               → terminal
```

**Attempt statuses (payment_attempts.status):**
`STARTED → SUCCEEDED | FAILED | UNKNOWN | RECONCILED`

**Routing snapshot:** every attempt records the selected gateway, route rule, and response
into `payment_attempts` for forensic accountability and routing optimization.

**Layering violations:** `PaymentIntentService` imports `InvoiceService` and
`SubscriptionService` directly — appropriate for orchestration but creates a coupling arc
that should be documented as an explicit allowed cross-domain dependency.

**Cohesion assessment:** High. Payments is the largest package (138 files) but well
sub-structured into `capacity/`, `disputes/`, `refund/`, `routing/`, `recovery/`.

---

### 5.3 `billing` — Invoicing, Credits, Discounts, Tax, Revenue Recognition

**Ownership:** Invoice lifecycle, credit/discount application, tax calculation.

**Core classes:**
- `InvoiceService` — creates, transitions, recomputes invoices.
- `InvoiceTotalService` — recomputes total from lines; enforces grand total invariant.
- `InvoiceInvariantService` — validates expected = actual total.
- `DiscountService` — creates and redeems discounts against invoices.
- `CreditNoteService` — credit note issuance and application to open invoices.
- `TaxCalculationService` — applies tax profiles to invoice lines.
- `RevenueRecognitionScheduleService` — generates monthly amortization schedule after
  payment success.
- `RevenueWaterfallProjectionService` — maintains waterfall projection.
- `RevenueRecognitionGuard` — blocks re-recognition of already-recognized schedules (V61).

**Invoice state machine:**
```
DRAFT → OPEN → PAST_DUE → PAID
             └→ VOID
```

**Credit application logic:** `CreditNoteService` applies available balance to reduce
`due_amount` on `OPEN` invoices. The service blocks over-application beyond available
credit.

**Period overlap guard:** `InvoiceService` rejects duplicate invoice creation for the same
subscription+period window. Evidence: guard logic in service layer + `invoice_sequences` table.

**Revenue recognition:** Each paid subscription generates a `RevenueRecognitionSchedule`
with monthly entries. The `RevenueRecognitionScheduler` processes `PENDING` entries in
daily batches, posting `REVENUE_RECOGNIZED` journal entries into the ledger. The
`RevenueCatchUpService` handles backfill of missed recognition periods.

**Weakness:** Revenue schedule generation failure after payment success is intentionally
non-blocking (caught exception). There is no dedicated repair queue for schedules that
failed to generate — they must be caught by `RevenueRecognitionDriftChecker` or the
`RevenueScheduleRegenerateAction` repair action.

---

### 5.4 `ledger` — Double-Entry Accounting

**Ownership:** Immutable financial ledger.

**Core classes:**
- `LedgerService` — central posting service.
- `LedgerPostingPolicy` — validates positive amounts, DR=CR balance on each entry.
- `LedgerEntryFactory` — constructs balanced journal entries.
- `LedgerReversalService` / `LedgerReversalServiceImpl` — reversal-only correction service.
- `ImmutableLedgerGuard` — service-layer guard; database trigger (V56) is the final backstop.
- `AccountSeeder` — initializes chart-of-accounts entries at startup.
- `RevenueRecognitionPostingService` — posts `REVENUE_RECOGNIZED` entries from schedule.

**Entities:**
- `LedgerAccount` — chart-of-accounts entry (`ASSET | LIABILITY | INCOME | EXPENSE`).
- `LedgerEntry` — journal header (reference type, currency, `reversal_of_entry_id`).
- `LedgerLine` — individual DR/CR leg (amount must be > 0 per DB `CHECK`).

**Immutability:**
- Service layer: `ImmutableLedgerGuard` throws on mutation attempts.
- Database layer: `trg_ledger_entries_immutable` and `trg_ledger_lines_immutable` (V56)
  raise `raise_exception` (SQLSTATE 'P0001') on any UPDATE or DELETE.

**Invariants enforced:**
1. `LedgerPostingPolicy` rejects any entry where Σ(DEBIT) ≠ Σ(CREDIT).
2. All line amounts must be positive (DB `CHECK ck_ledger_line_amount`).
3. Reversal cannot reverse a reversal (`reversal_of_entry_id` guard in service).
4. Duplicate reversal of same original entry is blocked.

**Weaknesses:**
- No accounting period close/lock mechanism. Any period can be posted to retroactively.
- No multi-currency revaluation or realized/unrealized FX gain/loss entries.
- No multi-book support (statutory vs. management ledger separation).

---

### 5.5 `subscription` — Subscription V2 Lifecycle

**Ownership:** Subscription creation, state transitions, and scheduling.

**Core classes:**
- `SubscriptionService` / `SubscriptionServiceImpl` — create, activate, pause, resume,
  cancel, expire.
- `SubscriptionStateValidator` — validates legal state transitions via `StateMachineValidator`.
- `SubscriptionScheduleService` — manages future subscription operations table.
- Entities: `SubscriptionV2`, `SubscriptionSchedule`.

**Status machine:**
```
INCOMPLETE → TRIALING → ACTIVE → PAST_DUE → CANCELLED
                                └→ PAUSED  → ACTIVE
                                └→ EXPIRED (by scheduler)
```

**Guard: duplicate active subscription** — service blocks creating a second active
subscription for the same merchant+customer+product combination.

**Cross-entity consistency checks:**
- Merchant must own the customer.
- Price must belong to the selected product.
- Effective price version is resolved at creation time.

**Weaknesses:**
- No entitlement model coupled to subscription status transitions.
- No seat-count or usage-metered billing dimension.
- `cancelAtPeriodEnd` is modeled but pause/restart proration rules are not visibly
  implemented in the core service.

---

### 5.6 `risk` — Risk Engine and Manual Review

**Ownership:** Pre-payment risk scoring, rule evaluation, manual review queue.

**Core classes:**
- `RiskService` — baseline checks: IP blocklist lookup, velocity recording.
- `RiskRuleService` — loads active `RiskRule` entities, invokes `RuleEvaluator` chain.
- `RiskDecisionService` — aggregates rule scores, applies MAX-action semantics
  (`BLOCK > REVIEW > CHALLENGE > ALLOW`).
- `RuleEvaluator` implementations:
  - `BlocklistIpEvaluator` — checks `ip_blocklist`.
  - `IpVelocityEvaluator` — counts `risk_events` by IP in 10-minute window.
  - `DeviceReuseEvaluator` — counts `risk_events` by device_id in 10-minute window.
  - `UserVelocityEvaluator` — counts `risk_events` by user_id in 10-minute window.
- `ManualReviewService` — creates/assigns/resolves `ManualReviewCase` entities.
- `ManualReviewEscalationService` — escalates overdue cases.
- `RiskScoreDecayService` — decays stored risk scores over time (V64).
- `RiskDecisionExplainer` — provides human-readable explanation of scoring decision.
- `RiskPostureService` — merchant-level posture summary endpoint.

**Weakness (critical):**
The `PaymentIntentService` builds `RiskContext` with `ip = null` and `deviceId = null`
before calling `RiskDecisionService.evaluate()`. This means `IpVelocityEvaluator` and
`DeviceReuseEvaluator` receive null inputs and cannot fire their rules effectively.
The most important risk signals at the payment gate — IP velocity and device reuse —
are currently non-functional in the core confirm path.

---

### 5.7 `dunning` — Retry Policy and Failure Recovery

**Ownership:** Subscription payment failure retry orchestration.

**Core classes:**
- `DunningPolicyService` — CRUD for `DunningPolicy` entities
  (maxAttempts, retryOffsetsJson, graceDays, terminalStatus).
- `DunningServiceV2` / `DunningServiceV2Impl` — core processing:
  validates subscription is PAST_DUE and invoice is still OPEN;
  executes retry via `PaymentGatewayPort`.
- `DunningSchedulerV2` — batch claim with `SKIP LOCKED`; runs per policy.
- `DunningStrategyService` — routes failed attempt by `FailureCategory` to decision
  (STOP_EARLY, RETRY, RETRY_WITH_BACKUP, EXHAUSTED).
- `FailureCodeClassifier` — maps gateway response codes to `FailureCategory` enum.
- `BackupPaymentMethodSelector` — selects backup method from
  `SubscriptionPaymentPreference`.
- `DunningDecisionAuditService` — appends decision audit records.

**Policy model:** `DunningPolicy` stores `retryOffsetsJson` (array of retry delay offsets
in hours from initial failure). The policy is merchant-scoped. Terminal action after
exhaustion is configurable as `SUSPEND` or `CANCEL`.

**Failure categories (V62):**
- `INSUFFICIENT_FUNDS` → retry later
- `CARD_EXPIRED` → stop early
- `DO_NOT_HONOR` → retry with backup
- `HARD_DECLINE` → stop early
- `SOFT_DECLINE` → retry
- `FRAUD_BLOCK` → stop early / escalate

**Weakness:** No customer communication (email/SMS/webhook notification) is wired into
the dunning engine itself. The dunning service only schedules retries and transitions
subscription state.

---

### 5.8 `recon` — Reconciliation Pipeline

**Ownership:** Settlement reconciliation and mismatch lifecycle.

**Core classes:**
- `ReconciliationService` — daily report generation (V13 model).
- `AdvancedReconciliationService` — multi-layer reconciliation:
  - payment vs ledger settlement,
  - ledger vs settlement batch,
  - settlement batch vs external statement.
- `SettlementBatchService` — fee/reserve/net decomposition.
- `StatementImportService` — CSV parser → `ExternalStatementImport` aggregate.
- `OrphanGatewayPaymentChecker` — detects gateway payments with no corresponding
  `payment_intents_v2` record.
- `DuplicateSettlementChecker` — detects duplicate `settlement_batch` entries.
- `ReconExpectationClassifier` — classifies mismatches by severity (`ReconSeverity`).
- `NightlyReconScheduler` — runs reconciliation daily with advisory lock to prevent
  concurrent overwrite races.

**Mismatch lifecycle:**
```
OPEN → ACKNOWLEDGED → RESOLVED
     └→ IGNORED (explicit operator decision)
```

**Weakness (critical):** `StatementImportService` parses and stores only aggregate CSV totals
in `ExternalStatementImport`. Individual statement lines are not persisted. This makes
exact-line-item reconciliation against gateway settlement files impossible purely through
the database, requiring manual off-system analysis.

---

### 5.9 `outbox` — Transactional Outbox

**Ownership:** Reliable async domain event dispatch.

**Core classes:**
- `OutboxService` — publisher (`publish()` using `Propagation.REQUIRED`) and poller
  (`lockDueEvents()`, `processSingleEvent()` using `Propagation.REQUIRES_NEW`).
- `OutboxEventHandlerRegistry` — maps event type strings to `OutboxEventHandler` beans.
- `OutboxLeaseHeartbeat` — renews `lease_expires_at` every ~60s for long-running handlers.
- `OutboxLeaseRecoveryService` — recovers events whose `lease_expires_at < NOW()` back to NEW.
- `OutboxPrioritySelector` — optional priority ordering for event dispatch.
- `OutboxPoller` (`outbox.scheduler`) — `@Scheduled(fixedRateString)`, claims events with
  `SELECT … FOR UPDATE SKIP LOCKED`.

**Status lifecycle:**
```
NEW → PROCESSING → DONE
              └→ NEW  (retry, backoff: [5,15,30,60] minutes)
              └→ FAILED (attempts ≥ 5 → DeadLetterMessage INSERT)
```

**Schema evolution:** V11 (initial), V47 (DLQ hardening), V58 (aggregate ordering
`aggregate_sequence`, lease heartbeat `lease_expires_at`).

**Failure categorization:** `failure_category` on `OutboxEvent` is one of:
`TRANSIENT_ERROR | PAYLOAD_PARSE_ERROR | ACCOUNTING_ERROR | BUSINESS_RULE_VIOLATION |
DEDUP_DUPLICATE | HANDLER_NOT_FOUND | UNKNOWN`.

**Weakness:** Unknown handler type is marked DONE to avoid infinite retries. This silently
drops events when handler registration drifts. There is no alerting on `HANDLER_NOT_FOUND`
category in the observable path.

---

### 5.10 `events` — Append-Only Domain Event Log

**Ownership:** Immutable business event log for audit, replay, and projection fan-out.

**Core classes:**
- `DomainEventService` — appends events to `domain_events` table.
- `DomainEventReplayService` — filtered replay (by merchant, aggregate, time window).
- `ReplayValidationService` — validates invariants within a replay window:
  duplicate invoice creation, orphan payment/subscription relationships, global ledger balance.
- `DomainEventSchemaRegistry` / `EventSchemaValidator` — schema version compatibility checks.
- `DomainEventController` — endpoints for replay, validation, event query.

**Metadata on every event:** `eventVersion`, `schemaVersion`, `correlationId`, `causationId`,
`aggregateType`, `aggregateId`, `merchantId` (introduced in V29, hardened in V59).

**Replay safety policy:** Certain event types are classified as `BLOCKED`, `IDEMPOTENT_ONLY`,
or `ALLOW` for replay. Dangerous event types (e.g., `PAYMENT_CAPTURED`) cannot be replayed
freely — they require explicit idempotency-guarded handler mode.

**Weakness:** Replay validation is window-bound. If a parent event (e.g., invoice creation)
lies outside the requested replay window, the validator reports orphan payment records as
false positives.

---

### 5.11 `platform` — Cross-Cutting Infrastructure

**Ownership:** All infrastructure concerns shared across domains.

#### Idempotency (`platform.idempotency`)
- `IdempotencyFilter` — servlet filter computing endpoint+body fingerprint.
- `IdempotencyService` / `IdempotencyRecordService` — DB source-of-truth CRUD.
- `RedisIdempotencyStore` — Redis response cache (TTL: `PT24H` default).
- `IdempotencyConflictDetector` — detects endpoint/body mismatch for the same key.
- `IdempotencyCheckpointService` — step-level checkpoint for multi-step operations.
- `IdempotencyCleanupScheduler` — purges expired keys daily.
- Table: `idempotency_keys` (V35, hardened in V51); `idempotency_checkpoints` (V51).

#### Rate Limiting (`platform.ratelimit`)
- `RedisSlidingWindowRateLimiter` — Lua atomic sliding window in Redis.
- `RateLimitInterceptor` — Spring MVC interceptor; applies per-endpoint policy.
- `RateLimitPolicy` enum — policy catalog: `AUTH_BY_IP`, `AUTH_BY_EMAIL`,
  `PAYMENT_CONFIRM`, `WEBHOOK_INGEST`, `APIKEY_GENERAL`.
- DB fallback: `rate_limit_events` table (V36) — logged but DB is not the enforcement store.
- Master switch: `app.rate-limit.enabled=true`.

#### Distributed Locks (`platform.lock`)
- `DistributedLockService` — Redis-based lock with Lua-safe release (lock owner verification).
- `FencingTokenService` — monotonic INCR via `Redis INCR` on fence key; stores
  `last_fence_token` on `subscriptions_v2`, `payment_intents_v2`, `invoices` (V52).
- `LockLeaseHeartbeat` — extends lock TTL for long-running operations.
- `LockScriptRegistry` — pre-loaded Lua scripts for acquire and release.
- `distributed_lock_audit` table — append-only lock telemetry (V52).

#### Request Context (`platform.context`)
- `RequestContextFilter` — populates `RequestContext` (requestId, correlationId, merchantId).
- `CorrelationIdFilter` / `RequestIdFilter` / `RequestLoggingFilter` — MDC enrichment.

#### RLS Tenant Context (`platform.db.rls`)
- `RlsTenantContextConfigurer` — calls `SET LOCAL app.current_merchant_id = ?` inside
  `@Transactional` methods for the four RLS-protected tables.
- **Scope limitation:** Only four tables have RLS enabled (V67). Application-layer merchant
  ownership checks are the primary isolation mechanism for all other tables.

#### Integrity Checks (`platform.integrity`)
- `IntegrityRunOrchestrator` — dispatches registered `IntegrityChecker` implementations.
- Domain-specific checkers: `billing/`, `events/`, `ledger/`, `payments/`, `recon/`,
  `revenue/` sub-packages.
- Tables: `integrity_check_runs`, `integrity_check_findings`, `integrity_check_results`,
  `repair_actions_audit` (V57).

#### Repair Actions (`platform.repair`)
- `RepairActionRegistry` — registry of named `RepairAction` beans.
- Actions: `InvoiceRecomputeAction`, `LedgerSnapshotRebuildAction`,
  `OutboxEventRetryAction`, `ProjectionRebuildAction`, `ReconRunAction`,
  `RevenueScheduleRegenerateAction`, `WebhookDeliveryRetryAction`.
- `RepairAdminController` — trigger repair actions via API.

#### Business Effect Dedup (`platform.dedup`)
- `BusinessEffectDedupService` — Redis + DB two-tier deduplication for idempotent effects.
- `BusinessEffectFingerprint` entity (`business_effect_fingerprints` table, V38).
- `BusinessEffectType` enum — classifies effects for fingerprint namespacing.

#### Observability (`platform.health`, `platform.slo`, `platform.metrics`)
- `DeepHealthController` — composite health report beyond Spring Actuator.
- `DlqDepthHealthIndicator` — health degradation when DLQ depth exceeds threshold.
- `OutboxLagHealthIndicator` — health check for outbox processing lag.
- `ProjectionLagHealthIndicator` — health check for projection staleness.
- `SloStatusService` — tracks SLO status per domain.
- `FinancialMetrics` — Micrometer counters/gauges for financial flows.


---

## 6. Database Schema

### 6.1 Core Foundation Tables (V1–V9)

| Table | PK | Key Indexes | Notable Constraints |
|---|---|---|---|
| `users` | BIGSERIAL | `email` (unique), `status`, `is_deleted` | `email UNIQUE` |
| `user_roles` | `(user_id, role)` | FK → `users` | Composite PK |
| `membership_tiers` | BIGSERIAL | `name` (unique), `level` | `name UNIQUE` |
| `membership_plans` | BIGSERIAL | `tier_id`, `type`, `is_active` | FK → `membership_tiers` |
| `subscriptions` | BIGSERIAL | `(user_id, status, end_date)`, `(auto_renewal, next_billing_date)` | FK → users, plans |
| `subscription_history` | BIGSERIAL | `subscription_id` | FK → subscriptions |
| `payment_intents` | BIGSERIAL | `user_id`, `status` | V1 legacy |
| `payments` | BIGSERIAL | `user_id`, `intent_id`, `status` | V1 legacy |
| `invoices` | BIGSERIAL | `subscription_id`, `status`, `due_date` | FK → subscriptions |
| `invoice_lines` | BIGSERIAL | `invoice_id` | FK → invoices |
| `credit_notes` | BIGSERIAL | `invoice_id`, `merchant_id` | FK → invoices |
| `ledger_accounts` | BIGSERIAL | `name` (unique) | `UNIQUE (name)`, `CHECK account_type` |
| `ledger_entries` | BIGSERIAL | `(reference_type, reference_id)` | `CHECK entry_type`, `CHECK ref_type` |
| `ledger_lines` | BIGSERIAL | `entry_id`, `account_id` | `CHECK direction`, `CHECK amount > 0` |
| `refunds` | BIGSERIAL | `payment_id` | `CHECK amount > 0` |

### 6.2 Merchant / Customer / Catalog (V15–V19)

| Table | Notable Design |
|---|---|
| `merchant_accounts` | `code` UNIQUE, `status` enum, `mode` (LIVE/SANDBOX) |
| `merchant_users` | `(merchant_id, user_id)` UNIQUE — user can belong to one merchant |
| `merchant_settings` | Auto-provisioned on merchant create; 1:1 with merchant |
| `customers` | `merchant_id` FK, PII fields encrypted via `EncryptedStringConverter` |
| `customer_notes` | Text blob, FK → customers |
| `products` | `merchant_id` + `name` scoped uniqueness |
| `prices` | FK → product, FK → merchant |
| `price_versions` | Effective-date ranged versioning; non-overlapping per price |
| `subscriptions_v2` | `merchant_id`, `customer_id`, `product_id`, `price_id`, `status`, `last_fence_token` (V52) |
| `subscription_schedules` | Future operation scheduling table |
| `payment_methods` | `merchant_id`, `customer_id`, `type`, `is_default` |
| `payment_method_mandates` | Recurring payment authorization |
| `payment_intents_v2` | `merchant_id`, `customer_id`, `invoice_id`, `last_fence_token` (V52), `version` for optimistic lock |
| `payment_attempts` | `(payment_intent_id, attempt_number)` UNIQUE; `routing_snapshot_json` (V37) |

### 6.3 Financial Hardening Tables (V25–V26, V55–V56)

- `refunds_v2` — supersedes V8 `refunds`; adds `idempotency_key`, `status` machine.
- `disputes` — `(payment_id)` unique active-dispute constraint; `reserve_posted`,
  `resolution_posted` boolean flags prevent double-posting.
- `dispute_evidence` — FK → disputes; evidence files and text.
- `payments.captured_amount_minor / refunded_amount_minor / disputed_amount_minor` (V55)
  — BIGINT minor-unit columns with DB `CHECK chk_payment_capacity`.
- `ledger_entries.reversal_of_entry_id` (V56) — FK self-reference for reversal lineage.
- Triggers `trg_ledger_entries_immutable`, `trg_ledger_lines_immutable` (V56).

### 6.4 Platform Hardening Tables (V34–V53)

| Table | Purpose |
|---|---|
| `feature_flags` | Boolean feature toggles by key |
| `job_locks` | Named lock registry (separate from advisory lock mechanism) |
| `idempotency_keys` | Composite PK: `(merchant_id, idempotency_key)` (after V51 backfill) |
| `idempotency_checkpoints` | Step-level progress for multi-step idempotent operations |
| `business_effect_fingerprints` | Redis+DB dedup for business effects |
| `distributed_lock_audit` | Append-only lock acquisition telemetry; fence_token field |
| `scheduler_execution_history` | Per-job execution outcomes |
| `integrity_check_runs` | Run metadata per integrity scan |
| `integrity_check_findings` | Individual invariant findings with severity |
| `integrity_check_results` | Aggregate results per run |
| `repair_actions_audit` | Audit of triggered repair actions |

### 6.5 RLS-Enabled Tables (V67)

PostgreSQL Row-Level Security is enabled and enforced (`FORCE ROW LEVEL SECURITY`) on:
- `customers`
- `subscriptions_v2`
- `invoices`
- `payment_intents_v2`

All four policies use `USING (merchant_id = NULLIF(current_setting('app.current_merchant_id', true), '')::BIGINT)`.

**Enforcement gap:** 90 other merchant-scoped tables do not have RLS enabled. Their isolation
depends entirely on application-layer service checks.


---

## 7. Migration Evolution

### 7.1 Phase Breakdown

| Version Range | Phase | What Was Introduced |
|---|---|---|
| V1 | Foundation | users, tiers, plans, subscriptions v1, payment_intents, payments, webhook_events, dead_letter_messages, invoices, invoice_lines, credit_notes |
| V2 | History | subscription_history |
| V3–V5 | Refinements | Additional subscription and payment columns |
| V6–V7 | (Unknown) | Intermediate refinements before ledger |
| V8 | Ledger + Refunds | ledger_accounts, ledger_entries, ledger_lines, refunds; DR/CR constraints |
| V9 | (Billing columns) | Additional invoice/billing fields |
| V10 | Dunning | dunning_attempts; SKIP LOCKED index |
| V11 | Outbox | outbox_events; status constraint; poll index |
| V12 | Risk | risk_events, ip_blocklist; velocity indexes |
| V13 | Recon | settlements, recon_reports, recon_mismatches |
| V14 | Domain Events | domain_events; causal metadata |
| V15 | Merchant Foundation | merchant_accounts, merchant_users, merchant_settings |
| V16 | Customer Domain | customers (encrypted PII fields), customer_notes |
| V17 | Catalog | products, prices, price_versions |
| V18 | Subscriptions V2 | subscriptions_v2, subscription_schedules |
| V19 | Payment Methods | payment_methods, payment_method_mandates |
| V20 | Payment Intents V2 | payment_intents_v2, payment_attempts; attempt uniqueness |
| V21 | Gateway Routing | gateway_route_rules, gateway_health |
| V22 | Billing Enhancements | invoice_sequences, discounts, discount_redemptions |
| V23 | Tax Profiles | tax_profiles, customer_tax_profiles |
| V24 | Revenue Recognition | revenue_recognition_schedules |
| V25 | Refunds V2 | refunds_v2, payment amount tracking columns |
| V26 | Disputes | disputes, dispute_evidence; uniqueness/capacity constraints |
| V27 | Dunning Policies | dunning_policies, subscription_payment_preferences |
| V28 | Webhooks | merchant_webhook_endpoints, merchant_webhook_deliveries |
| V29 | Event Versioning | domain_events metadata: eventVersion, schemaVersion, causation/correlation |
| V30 | Projections | customer_billing_summary, ledger_balance_snapshots, merchant_daily_kpis, settlement_batches |
| V31 | Advanced Recon | mismatch lifecycle, statement_imports, settlement_batch_items |
| V32 | Risk Engine | risk_rules, risk_decisions, manual_review_cases |
| V33 | Merchant Auth | merchant_api_keys, merchant_modes |
| V34 | Ops Flags | feature_flags, job_locks, audit_entries |
| V35 | Idempotency | idempotency_keys table (initial) |
| V36 | Rate Limits | rate_limit_events |
| V37 | Routing Snapshot | routing_snapshot_json column on payment_attempts |
| V38 | Dedup | business_effect_fingerprints |
| V39–V44 | Integrity/Repair | integrity_check_runs/findings/results, repair_actions_audit; projection tables |
| V45 | Revenue Hardening | Revenue guard constraints |
| V46 | Refund/Dispute Hardening | Additional guards |
| V47 | Outbox DLQ Hardening | failure_category, processing_owner, handler_fingerprint |
| V48 | Webhook Hardening | Delivery dedup fingerprint, auto-disable counters |
| V49 | Support Ops | support_cases, support_notes |
| V50 | Platform Contracts | Platform contract foundation tables |
| V51 | Idempotency Hardening | Status lifecycle, checkpoints, stuck-PROCESSING cleanup indexes |
| V52 | Fencing Tokens | last_fence_token on subscriptions_v2, payment_intents_v2, invoices; distributed_lock_audit |
| V53 | Scheduler Tracking | scheduler_execution_history |
| V54 | Payment Unknown Outcomes | unknown_gateway_outcome fields on payment_attempts; reconciliation_at |
| V55 | Payment Capacity | Minor-unit columns + DB CHECK constraints on payments |
| V56 | Ledger Immutability | reversal_of_entry_id; trigger functions + triggers on ledger tables |
| V57 | Integrity Audit | integrity_check_runs/findings/results refinements; partition_management_log |
| V58 | Outbox Ordering/Leasing | aggregate_sequence, lease_expires_at on outbox_events |
| V59 | Event Schema Versioning | event_version, schema_version columns on domain_events |
| V60 | Recon FX Fields | expectation/FX columns on recon tables |
| V61 | Revenue Guards | RevenueRecognitionGuard CHECK constraints |
| V62 | Dunning Intelligence | failure_category intelligence fields on dunning_attempts |
| V63 | Invoice Correctness | Invoice correctness guard columns |
| V64 | Risk Decay Fields | score_decay_applied_at, case_fields on risk entities |
| V65 | Additional Projections | subscription_status_projection, payment_summary_projection, etc. |
| V66 | Ops Timeline | ops_timeline_events hardening |
| V67 | DB Hardening | Partial indexes + partitioning readiness + RLS on 4 tables |
| V68 | Financial Audit + API Versioning | financial_audit columns, merchant_api_versions |
| V69 | Elite Testing Infrastructure | test_execution_runs, performance_baselines |

### 7.2 Migration Safety Assessment

**Strong practices:**
- All migrations are irreversible SQL (no rollback scripts) — consistent with Flyway baseline approach.
- V56 uses `CREATE OR REPLACE FUNCTION` for trigger idempotency.
- V67 correctly omits `CONCURRENTLY` in `CREATE INDEX` because Flyway executes inside a transaction.

**Risks and concerns:**
1. **V51 data migration risk:** V51 includes `UPDATE idempotency_keys SET …` on potentially large
   tables. On a live database with millions of idempotency keys, this is a full table scan and
   will hold a table lock during migration — a maintenance window operation.
2. **V57 integrity table evolution:** Multiple integrity check table schemas across V39–V44
   and V57 could produce conflicting column definitions if applied to a partially-migrated
   baseline. A full empty-DB `flyway migrate V1→V69` chain verification should be run in CI.
3. **Partitioning readiness (V67) is schema-only:** The `partition_management_log` registry
   and PL/pgSQL partition helper function are installed but actual table conversion to
   `PARTITION BY RANGE` requires a separate DBA maintenance window.


---

## 8. Ledger System

**Implementation status: FULLY IMPLEMENTED with strong invariants.**

### 8.1 Model

Double-entry ledger implemented across three tables:
- `ledger_accounts` — chart of accounts (`ASSET | LIABILITY | INCOME | EXPENSE`; currency).
- `ledger_entries` — journal header per business event (`entry_type`, `reference_type`,
  `reference_id`, `reversal_of_entry_id`).
- `ledger_lines` — individual debit/credit legs (`direction IN ('DEBIT','CREDIT')`,
  `amount > 0`).

### 8.2 Posting Flow

```
LedgerEntryFactory.build(entryType, referenceType, referenceId, lines)
    │
    └─ LedgerPostingPolicy.validate(entry)
        ├─ All line amounts > 0
        └─ Σ DEBIT == Σ CREDIT
            │
            └─ LedgerService.post(entry) [persists LedgerEntry + LedgerLines]
                └─ ImmutableLedgerGuard state check
```

### 8.3 Immutability Enforcement

Two-layer protection:
1. **Service layer:** `ImmutableLedgerGuard.assertNotModifiable()` throws
   `InvariantViolationException` on any mutation attempt.
2. **Database layer:** `trg_ledger_entries_immutable` and `trg_ledger_lines_immutable`
   (V56) raise PostgreSQL exception `P0001` on `UPDATE` or `DELETE`.

This means even a direct SQL mutation by a DBA or a buggy migration will be rejected
unless the trigger is explicitly dropped — which would require deliberate action.

### 8.4 Reversal Model

`LedgerReversalService.reverse(originalEntryId, reason)`:
- Loads the original entry.
- Validates it is not itself a reversal (no recursive reversal).
- Validates it has not already been reversed (duplicate reversal guard).
- Creates a mirror entry with reversed debit/credit directions.
- Sets `reversal_of_entry_id` → original entry.

### 8.5 Balancing Guarantees

Every `LedgerEntry` persisted in the system satisfies Σ(DEBIT lines) == Σ(CREDIT lines)
by construction (`LedgerPostingPolicy`). The database `CHECK` constraint on
`ledger_lines.amount > 0` prevents negative amounts from circumventing the balance check.

### 8.6 Known Gaps

| Gap | Impact |
|---|---|
| No accounting period close/lock | Any period can be retroactively posted to |
| No multi-book support | Statutory vs management ledger cannot be separated |
| No FX revaluation entries | Multi-currency positions have no P&L revaluation |
| No trial balance endpoint | No built-in balance sheet query at point-in-time |

---

## 9. Payment System

**Implementation status: FULLY IMPLEMENTED (v2 model); v1 model is legacy/incomplete.**

### 9.1 Intent Model

`payment_intents_v2` — the current model. Key design:
- Linked to `merchant_id`, `customer_id`, optional `invoice_id`, optional `subscription_id`.
- `client_secret` — unique per intent, for client-side confirmation.
- `version` — optimistic lock version (JPA `@Version`).
- `last_fence_token` — stale-writer protection (V52).
- `capture_mode` — `AUTO | MANUAL`.

### 9.2 Attempt Model

`payment_attempts` — one per gateway call:
- `UNIQUE (payment_intent_id, attempt_number)` — prevents duplicate attempts at same
  sequence position.
- `retriable` flag — controls whether a FAILED intent can be re-confirmed.
- `failure_category` — maps gateway response to internal classification.
- `routing_snapshot_json` (V37) — records gateway selection evidence.
- `unknown_gateway_outcome_at` (V54) — marks attempts with ambiguous gateway response
  for reconciliation.

### 9.3 Gateway Integration

The production gateway integration is abstracted via `PaymentGatewayPort` (hexagonal port).
`GatewayController` + `SimulatedPaymentGateway` implement a local OTP-based gateway
emulator for development. The actual production adapter is not present in the repository
codebase — the port interface is defined but the external adapter is injected at deployment.

**Risk:** `GatewayController` is exposed via `permitAll` in `SecurityConfig`. This emulator
endpoint has no production-environment guard and could be accidentally deployed.

### 9.4 Refund Model

`refunds_v2` (V25) — idempotent refund creation:
- Fingerprint dedup via `idempotency_key`.
- Capacity check: `captured_amount_minor >= refunded_amount_minor + disputed_amount_minor`.
- On success: `RefundAccountingService` posts reversal `REFUND_ISSUED` journal entry.
- Updates `payments.refunded_amount_minor` and syncs minor-unit field.

### 9.5 Dispute Model

`disputes` + `dispute_evidence` (V26):
- `UNIQUE` active dispute per `payment_id`.
- `reserve_posted` flag prevents double-posting of reserve accounting entry on open.
- `resolution_posted` flag prevents double-posting of resolution entry on close.
- `DisputeCapacityService` validates capacity before opening.
- On `WON`: reserve released back; on `LOST`: reserve transferred to expense.

### 9.6 Unknown Outcomes

`payment_attempts.status = UNKNOWN` (V54) — when gateway response is ambiguous:
- `PaymentRecoveryService` runs periodic reconciliation against the gateway.
- On reconciliation: status updated to `RECONCILED` with actual outcome.

### 9.7 Payment Flow Diagram

```
POST /api/v2/payment-intents/{id}/confirm
│
├─ Load intent (FOR UPDATE pessimistic lock)
├─ SUCCEEDED? → idempotent 200
├─ CANCELLED? → 422
├─ FAILED + not retriable? → 422
├─ Risk evaluation (RiskDecisionService)
│   └─ ⚠ IP/device null → evaluators partially neutered
├─ Gateway route selection (GatewayRouteRule)
├─ PaymentAttempt INSERT (status=STARTED)
├─ GatewayPort.charge()
│   ├─ SUCCESS → intent=SUCCEEDED, attempt=SUCCEEDED
│   │            → invoice.markPaid() → subscription.activate()
│   │            → LedgerService.postCapture()
│   │            → RevenueSchedule.generate() [non-blocking]
│   │            → outbox.publish(PAYMENT_SUCCEEDED)
│   ├─ FAILURE  → attempt=FAILED(retriable?) → outbox.publish(PAYMENT_FAILED)
│   └─ UNKNOWN  → attempt=UNKNOWN → scheduled reconciliation
└─ Return PaymentIntentV2ResponseDTO
```

---

## 10. Billing System

**Implementation status: FULLY IMPLEMENTED with strong lifecycle controls.**

### 10.1 Invoice Lifecycle

```
(Subscription period triggers)
    │
    └─ InvoiceService.createForPeriod(subscription, periodStart, periodEnd)
        ├─ Period overlap guard (reject duplicate window)
        ├─ InvoiceSequenceService.next() → formatted invoice number
        ├─ InvoiceLine creation: PLAN_CHARGE
        ├─ CreditNoteService.applyAvailableCredits()
        ├─ DiscountService.applyEligibleDiscounts()
        ├─ TaxCalculationService.applyTax()
        ├─ InvoiceTotalService.recompute()
        └─ outbox.publish(INVOICE_CREATED)
```

### 10.2 Invoice State Transitions

```
DRAFT → OPEN → PAID (final)
             └→ PAST_DUE (payment failure + dunning)
             └→ VOID (explicit void — requires refund/reversal path)
```

### 10.3 Revenue Recognition

After invoice payment:
```
RevenueRecognitionScheduleService.generate(invoiceId, paidAt, planPeriodMonths)
    │
    └─ Creates N monthly RevenueRecognitionSchedule entries (status=PENDING)
        │
        └─ RevenueRecognitionScheduler (@Scheduled daily)
            └─ Batch process PENDING entries with recognition_date <= today
                └─ RevenueRecognitionPostingService.post()
                    └─ LedgerService.post(REVENUE_RECOGNIZED, ...)
```

`RevenueRecognitionGuard` (V61) blocks re-recognition of already RECOGNIZED schedules.
`RevenueCatchUpService` handles backfill for missed periods.

---

## 11. Subscription Engine

**Implementation status: FULLY IMPLEMENTED (v2 model). V1 model is supplementary legacy.**

### 11.1 State Machine

```
(New subscription)
    ├─ hasTrialPeriod? → TRIALING → ACTIVE (on trial-end billing)
    └─ noTrial?        → INCOMPLETE → ACTIVE (on first payment success)

ACTIVE   → PAST_DUE (payment failure)
         → PAUSED   → ACTIVE (on resume)
         → CANCELLED (explicit / exhausted dunning)
         → EXPIRED  (period-end, no renewal)
PAST_DUE → ACTIVE (dunning success)
         → SUSPENDED / CANCELLED (dunning exhaustion)
```

### 11.2 Coupling with Billing and Payments

- Subscription activation is triggered by `PaymentIntentService` on capture success
  (via `SubscriptionService.activate()`).
- Subscription billing is triggered by `RenewalScheduler` (`dunning.scheduler` package)
  which creates new invoices for active subscriptions approaching renewal.
- `cancelAtPeriodEnd` sets a deferred cancellation that `RenewalScheduler` respects
  at period boundary.

### 11.3 Schedule Table

`subscription_schedules` stores future operations (pause, cancel, tier change) with
`effective_at` timestamps. The scheduler processes these asynchronously.

### 11.4 Known Gaps

- No entitlement binding on status transitions.
- No usage-metered or seat-count billing dimensions.
- Pause/restart proration calculation is not visibly implemented.
- `SubscriptionConcurrencyIT` test exists but the production locking for concurrent
  subscription transitions relies on the JPA `@Version` optimistic lock on `subscriptions_v2`.

---

## 12. Risk Engine

**Implementation status: IMPLEMENTED with rule engine, manual review queue, and score decay.
Critical gap: payment-confirm path passes null IP/device context.**

### 12.1 Rule Evaluation Architecture

```
RiskDecisionService.evaluate(RiskContext)
    │
    ├─ RiskRuleService.getActiveRules()
    ├─ for each rule:
    │   └─ RuleEvaluator.evaluate(RiskContext, RiskRule) → score + action
    │       ├─ BlocklistIpEvaluator   (ip → ip_blocklist lookup)
    │       ├─ IpVelocityEvaluator    (ip, window=10min → risk_events count)
    │       ├─ DeviceReuseEvaluator   (deviceId → risk_events count)
    │       └─ UserVelocityEvaluator  (userId → risk_events count)
    │
    ├─ Aggregate: MAX action wins (BLOCK > REVIEW > CHALLENGE > ALLOW)
    └─ If REVIEW: ManualReviewService.createCase()
```

### 12.2 Manual Review Queue

`ManualReviewCase` entity with lifecycle:
```
OPEN → IN_REVIEW → APPROVED | REJECTED | ESCALATED
```
`ManualReviewEscalationService` escalates cases that exceed SLA threshold.
`RiskMaintenanceScheduler` refreshes decayed scores and processes escalations.

### 12.3 Score Decay

`RiskScoreDecayService` (V64) — applies time-based decay to stored risk scores.
Decay policy reduces score over time, allowing temporarily flagged entities to recover.

### 12.4 Risk Context Gap

`PaymentIntentService.confirm()` constructs:
```java
RiskContext context = RiskContext.builder()
    .merchantId(intent.getMerchantId())
    .customerId(intent.getCustomerId())
    .ip(null)          // ← critical gap
    .deviceId(null)    // ← critical gap
    .build();
```
IP and device evaluators receive null and cannot make blocking or scoring decisions.
Only `UserVelocityEvaluator` is fully functional in the confirm path.

---

## 13. Dunning Engine

**Implementation status: FULLY IMPLEMENTED with policy-driven retry, failure intelligence,
backup payment methods, and terminal state management.**

### 13.1 Retry Policy Model

`DunningPolicy` entity fields:
- `maxAttempts` — maximum retry count before terminal.
- `retryOffsetsJson` — JSON array of retry delay hours (e.g. `[1, 6, 24, 72]`).
- `graceDays` — grace window after initial failure.
- `terminalStatus` — `SUSPEND` or `CANCEL`.

### 13.2 Processing Flow

```
DunningSchedulerV2 (@Scheduled)
    │
    ├─ SchedulerLockService.acquireAdvisoryLock("dunning-v2")
    └─ DunningAttemptRepository.findDueScheduled()
        └─ SELECT … FOR UPDATE SKIP LOCKED WHERE status='SCHEDULED' AND scheduled_at<=now
            │
            └─ DunningServiceV2Impl.processAttempt(attempt)
                ├─ Guard: subscription still PAST_DUE
                ├─ Guard: invoice still OPEN
                ├─ GatewayPort.charge(paymentMethod, amount)
                │
                ├─ SUCCESS:
                │   ├─ invoice.markPaid()
                │   ├─ subscription.activate()
                │   └─ cancel remaining SCHEDULED dunning attempts
                │
                └─ FAILURE:
                    ├─ FailureCodeClassifier.classify(responseCode) → FailureCategory
                    └─ DunningStrategyService.decide(category, attemptsRemaining):
                        ├─ STOP_EARLY   → terminal (SUSPEND or CANCEL)
                        ├─ RETRY        → schedule next attempt per retryOffsets
                        ├─ RETRY_BACKUP → BackupPaymentMethodSelector + schedule
                        └─ EXHAUSTED    → terminal (maxAttempts reached)
```

### 13.3 Known Gaps

- No customer notification orchestration within dunning core.
- No adaptive retry timing based on issuer cohort analysis.
- No automatic support case creation for high-value exhausted accounts.
- `DunningScheduler` (v1, legacy) and `DunningSchedulerV2` coexist — dual scheduler risk.

---

## 14. Reconciliation Engine

**Implementation status: IMPLEMENTED — multi-layer reconciliation with mismatch lifecycle.
Critical gap: statement import stores aggregate totals only, no line-level persistence.**

### 14.1 Reconciliation Layers

```
ReconciliationService (basic, V13 model):
    payment total vs invoice expected total
    → recon_reports (daily) + recon_mismatches

AdvancedReconciliationService (V31 model):
    Layer 1: payments vs ledger_entries (settlement reference match)
    Layer 2: ledger_entries vs settlement_batches
    Layer 3: settlement_batches vs ExternalStatementImport (aggregate totals)
    → recon_mismatches with type, severity, details
```

### 14.2 Mismatch Types (`MismatchType` enum)

- `PAYMENT_NOT_IN_LEDGER` — payment succeeded but no ledger entry.
- `LEDGER_NOT_IN_SETTLEMENT` — ledger entry with no corresponding settlement.
- `SETTLEMENT_NOT_IN_STATEMENT` — settlement batch not in external statement.
- `AMOUNT_MISMATCH` — amounts differ between layers.
- `ORPHAN_GATEWAY_PAYMENT` — gateway payment with no intent record.
- `DUPLICATE_SETTLEMENT_BATCH` — detected by `DuplicateSettlementChecker`.

### 14.3 Statement Import Limitation

`StatementImportService` parses CSV and aggregates to `ExternalStatementImport`
(total amount, count, source type). **No individual statement line rows are persisted.**
This means reconciliation Layer 3 can only compare totals, not match individual transactions.

### 14.4 Mismatch Lifecycle

```
OPEN → ACKNOWLEDGED (operator review) → RESOLVED
                                       └→ IGNORED
```

No automated journal entry generation for resolved mismatches.

---

## 15. Outbox Architecture

**Implementation status: FULLY IMPLEMENTED with lease-based heartbeat, aggregate ordering,
DLQ, and failure categorization.**

### 15.1 Schema

`outbox_events` table key fields:
- `status` — `NEW | PROCESSING | DONE | FAILED`
- `attempts` — retry counter
- `next_attempt_at` — backoff-controlled dispatch timestamp
- `processing_owner` — `hostname:pid` of claiming JVM
- `lease_expires_at` — heartbeat-refreshed lease expiry (V58)
- `aggregate_sequence` — monotonic per-aggregate ordering (V58)
- `failure_category` — last failure classification
- `correlation_id`, `causation_id`, `aggregate_type`, `aggregate_id` — event lineage

### 15.2 Delivery Guarantees

- **At-least-once delivery** within the same JVM process (no external broker).
- **Lease-based deduplication:** `processing_owner` + `lease_expires_at` prevents two
  threads from processing the same event concurrently.
- **DLQ routing:** after 5 failed attempts, event is copied to `dead_letter_messages` and
  marked `FAILED` to stop infinite retries.
- **Per-aggregate ordering:** `aggregate_sequence` allows handlers to enforce ordering
  within an aggregate's event stream.

### 15.3 Operational Risks

1. **Handler-not-found silent drop:** Unknown event types are marked DONE. No alerting
   is wired to `HANDLER_NOT_FOUND` failure category.
2. **Monolith-only dispatch:** Outbox is not connected to any external message bus. All
   downstream systems must be in the same JVM or receive outbox side-effects via webhooks.
3. **DLQ depth not auto-alarmed:** `DlqDepthHealthIndicator` checks DLQ depth but does
   not auto-page/alert on threshold breach outside of health endpoint polling.

---

## 16. Event System

**Implementation status: IMPLEMENTED — append-only domain event log with replay and
schema versioning. No external event bus integration.**

### 16.1 Domain Event Log

`domain_events` table — immutable append-only log. Every significant business event
is written here alongside the outbox row. Key metadata (V14, V29, V59):
- `event_type`, `aggregate_type`, `aggregate_id`
- `event_version`, `schema_version` — compatibility tracking
- `correlation_id`, `causation_id` — event lineage graph
- `merchant_id` — multi-tenant scoping
- `payload_json` — event body

### 16.2 Replay Architecture

`DomainEventReplayService` supports:
- Filtered replay (by merchant, aggregate type/id, time window).
- Validation mode (dry-run, no side effects).
- Projection rebuild mode (fan-out to registered projection updaters).

`ReplayValidationService` validates within the requested window:
1. No duplicate invoice creation.
2. No orphan payment records (no associated subscription activation).
3. Global ledger balance check.

**Limitation:** Window-bounded replay produces false-positive orphan warnings when
parent events predate the requested window.

### 16.3 Schema Registry

`DomainEventSchemaRegistry` — registers expected schema versions per event type.
`EventSchemaValidator` — validates incoming events against registered schema.
V59 added `event_version` and `schema_version` columns to `domain_events`.
CI compatibility checks for schema evolution are **not yet present in `build.yml`**.

### 16.4 Event Publishing Model

Events are published synchronously within the service transaction:
```
@Transactional service method
    └─ outboxService.publish(eventType, payload)  // outbox row (async dispatch)
    └─ domainEventService.append(eventType, payload) // audit log
```
There is no external broker (Kafka, RabbitMQ, SQS). Integration with external systems
requires the outbox handler to dispatch via the merchant webhook or a future HTTP adapter.

---

## 17. Redis Usage

**Implementation status: FULLY IMPLEMENTED for idempotency, rate limiting, distributed
locks, fencing tokens, caches, and projections. Graceful degradation to DB on Redis failure
is implemented for idempotency and rate limiting.**

### 17.1 Redis Key Namespaces

Defined in `RedisNamespaces`:
- `idempotency:{merchantId}:{key}` — cached idempotency responses (TTL: 24h)
- `ratelimit:{policy}:{subject}:{window}` — sliding window counters
- `lock:{resourceType}:{resourceId}` — distributed lock ownership token
- `fence:{resourceType}:{resourceId}` — fencing token INCR counter
- `projection:{type}:{id}` — cached projection results
- `routing:{gatewayRuleHash}` — gateway routing decision cache
- `search:{query}` — admin search result cache
- `timeline:{aggregateId}` — ops timeline cache

### 17.2 Sliding-Window Rate Limiter

`RedisSlidingWindowRateLimiter` — single Lua script that atomically:
1. `ZADD {key} {now_ms} {requestId}` — adds current request.
2. `ZREMRANGEBYSCORE {key} 0 {window_start}` — removes expired entries.
3. `ZCARD {key}` — counts requests in window.
4. Returns count vs. limit.

Redis failure falls back to `RateLimitEventEntity` DB logging (best-effort, not enforcing).

### 17.3 Distributed Lock

`DistributedLockService.acquire(resourceType, resourceId, ttl)`:
- Redis `SET {key} {owner} NX PX {ttl_ms}` via Lua for atomicity.
- Release: Lua script checks owner match before `DEL` (prevents accidental release of
  another owner's lock).
- `LockLeaseHeartbeat` renews TTL for long-running operations.
- Lock audit: every acquire/release is appended to `distributed_lock_audit` table.

### 17.4 Fencing Tokens

`FencingTokenService.nextToken(resourceType, resourceId)`:
- `Redis INCR {fence-key}` → monotonically increasing BIGINT.
- `last_fence_token` stored on `subscriptions_v2`, `payment_intents_v2`, `invoices`.
- `FenceAwareUpdater` validates presented token > `entity.lastFenceToken` before write.

**Adoption gap:** Fencing token enforcement is available via `FenceAwareUpdater` but
is not universally applied on all mutation paths — it requires explicit call-site adoption.

### 17.5 Degradation Behavior

`RedisAvailabilityServiceImpl` tracks Redis health. On `RedisConnectionFailureException`:
- Idempotency: falls back to DB-only (`idempotency_keys` table).
- Rate limiting: falls back to logging-only (permissive mode — no enforcement).
- Locks: falls back to PostgreSQL advisory locks via `AdvisoryLockRepository`.

**Risk:** Rate limiting in Redis-down mode is **permissive** (no enforcement). A Redis
outage opens the door to burst traffic during the degraded window.


---

## 18. Concurrency Model

### 18.1 Transaction Boundaries

All service methods use Spring `@Transactional` (REQUIRED propagation by default).
`OutboxService.processSingleEvent()` uses `REQUIRES_NEW` to isolate event handler failures
from each other and from the poller transaction.

### 18.2 Locking Strategy Summary

| Scenario | Mechanism | Evidence |
|---|---|---|
| Payment intent mutation | `SELECT … FOR UPDATE` (pessimistic) | `PaymentIntentService.loadLocked()` |
| Refund/dispute capacity check | Pessimistic lock on `payments` row | `RefundCapacityServiceImpl`, `DisputeCapacityServiceImpl` |
| Subscription state transition | JPA `@Version` (optimistic) + retry | `SubscriptionV2.version` field |
| Invoice state transition | Fencing token (`last_fence_token`) | `FenceAwareUpdater` |
| Outbox batch claim | `SELECT … FOR UPDATE SKIP LOCKED` | `OutboxEventRepository.lockDueEvents()` |
| Dunning batch claim | `SELECT … FOR UPDATE SKIP LOCKED` | `DunningAttemptRepository` |
| Scheduler singleton | PostgreSQL advisory lock | `SchedulerLockService.acquireAdvisoryLock()` |
| Recon report upsert | Row-level lock on `recon_reports` | `NightlyReconScheduler` |
| Distributed business lock | Redis SET NX + Lua release | `DistributedLockService` |
| Cross-JVM fencing | Redis INCR + `last_fence_token` column | `FencingTokenService` + `FenceAwareUpdater` |

### 18.3 Scheduler Overlap Prevention

`PrimaryOnlySchedulerGuard` + `SchedulerLockService`:
```
@Scheduled
void runJob() {
    if (!primaryOnlyGuard.isPrimary()) return;
    if (!schedulerLockService.tryAcquireAdvisoryLock(jobName)) return;
    // ... execute
    schedulerExecutionRecorder.record(jobName, outcome);
}
```

This prevents multiple JVM instances from running the same scheduler job concurrently
even without an external distributed coordinator (Quartz, ShedLock).

### 18.4 Optimistic Retry Template

`OptimisticRetryTemplate` (`platform.concurrency.retry`) provides a retry loop for
`OptimisticLockingFailureException` (JPA `@Version` conflicts). Default backoff policy:
`RetryBackoffPolicy` with configurable jitter via `RetryJitterStrategy`.

### 18.5 Concurrency Hazards

1. **Invoice concurrent update:** The `last_fence_token` approach relies on explicit
   `FenceAwareUpdater` call-sites. Service methods that update invoices without using
   `FenceAwareUpdater` bypass fencing protection.
2. **Subscription optimistic lock under high dunning load:** Multiple dunning threads
   could produce frequent `OptimisticLockingFailureException` retries for the same
   subscription under bursty failure conditions.
3. **Outbox handler re-entrant write:** If an outbox handler itself writes to the outbox
   (nested publish), the inner publish shares the `REQUIRES_NEW` transaction — this is
   safe by design since `publish()` uses `REQUIRED`.
4. **Redis lock release under thread interruption:** If a thread is interrupted before
   calling `LockHandle.release()`, the lock expires naturally after TTL. The audit table
   will show `expired=true`. Heartbeat covers long operations but not abrupt JVM termination.

### 18.6 Concurrency Tests

The repository includes dedicated concurrency integration tests:
- `PaymentConcurrencyIT` — concurrent payment confirms.
- `RefundConcurrencyIT` — concurrent refund requests.
- `DisputeConcurrencyIT` — concurrent dispute opens.
- `SubscriptionConcurrencyIT` — concurrent subscription transitions.
- `DunningConcurrencyIT` — concurrent dunning attempts.
- `ReconConcurrencyIT` — concurrent recon report writes.
- `WebhookConcurrencyIT` — concurrent webhook deliveries.
- `LedgerImmutabilityIT` — validates ledger trigger rejects mutations.

These tests use Testcontainers (PostgreSQL) and verify that locking mechanisms hold under
concurrent load.

---

## 19. Idempotency Guarantees

### 19.1 API-Level Idempotency

`IdempotencyFilter` intercepts all `POST`/`PATCH`/`PUT` requests with an `Idempotency-Key` header:

```
Header: Idempotency-Key: <merchant-generated UUID>

Filter logic:
  1. Look up (merchantId, key) in Redis cache + idempotency_keys table.
  2. Status=COMPLETED → return cached HTTP body + status code (no service call).
  3. Status=PROCESSING → return 409 CONFLICT (request in-flight guard).
  4. Status=not-found → set status=PROCESSING, proceed, store response.
```

`IdempotencyConflictDetector` also validates that a second request with the same key
but a different endpoint or request hash returns 422 (mismatched idempotency use).

### 19.2 Checkpoint-Level Idempotency

`IdempotencyCheckpointService` tracks multi-step operations:
- A step `(merchantId, idempotency_key, operationType, stepName)` is marked
  `COMPLETED` after success.
- On replay, completed steps are skipped — only pending steps are re-executed.
- This enables safe partial-retry of multi-step flows (e.g., invoice → payment → activate).

### 19.3 Business Effect Deduplication

`BusinessEffectDedupService` provides secondary deduplication at the effect level:
- Fingerprint computed from `(merchantId, effectType, referenceId, amount, …)`.
- Redis tier: `SET {fingerprint} 1 NX PX {ttl}` — fast rejection.
- DB tier: `business_effect_fingerprints` INSERT with unique constraint — durable rejection.
- Used in outbox handlers to prevent double-posting from at-least-once delivery.

### 19.4 Scheduler Idempotency

Schedulers are guarded at two levels:
1. Advisory lock prevents concurrent execution.
2. Processed entities are marked with terminal status (e.g., `DONE`, `SCHEDULED` → `SUCCESS`)
   before the transaction commits, preventing re-processing on re-run.

### 19.5 Webhook Idempotency

Inbound webhooks from payment gateway (`WebhookController`):
- HMAC signature verified from `payments.webhook.secret` property.
- Event processing protected by `BusinessEffectDedupService` fingerprint.
- `merchant_webhook_deliveries` table deduplicates outbound webhook deliveries by
  `delivery_fingerprint` (V48).

### 19.6 Known Idempotency Gaps

| Gap | Risk |
|---|---|
| Revenue schedule generation is non-blocking after payment capture | Missed schedule has no automatic repair trigger |
| Redis idempotency cache expiry (24h) | Requests replayed after 24h with same key may be re-processed if DB key also expired |
| Fencing token not universally applied | Some invoice mutation paths bypass `FenceAwareUpdater` |

---

## 20. Security Architecture

### 20.1 Authentication

- **Mechanism:** Stateless JWT (HS256 via JJWT 0.12.5).
- **Token issuer:** `AuthController` issues tokens on `/api/v1/auth/login` and
  `/api/v1/auth/register`.
- **Secret:** Configured via `app.jwt.secret` (expected to be externalized as env var in prod).
- **Filter:** `JwtAuthenticationFilter` — validates token, populates `SecurityContext`,
  supports token blacklist via `membership.filter` package.
- **BCrypt:** Work factor 12 (OWASP 2025 recommendation; default is 10).

### 20.2 Authorization

- **Method security:** `@EnableMethodSecurity(prePostEnabled = true)` on `SecurityConfig`.
- **Role checks:** `@PreAuthorize("hasRole('ADMIN')")` on admin endpoints.
- **Merchant ownership:** Service methods explicitly validate that the authenticated
  merchant owns the requested customer/subscription/invoice.
- **API keys:** `merchant_api_keys` table (V33) with hashed key values and `mode`
  (`LIVE | SANDBOX`) isolation. API key validation service is present but key-based
  auth flow is separate from JWT and not consistently enforced across all endpoints.

### 20.3 Public Routes

```
/api/v1/auth/**             — always public (login, register)
/api/v1/membership/plans/** — read-only public catalogue
/api/v1/plans/**            — read-only public catalogue
/gateway/**                 — fake gateway emulator (DEV ONLY — PRODUCTION RISK)
/api/v1/webhooks/**         — permitAll (HMAC verified in controller)
/swagger-ui/**, /v3/api-docs/** — Swagger UI
/actuator/health            — public health endpoint
/actuator/**                — ADMIN role required
```

**Critical risk:** `/gateway/**` is `permitAll` with no environment guard. If deployed
to production without explicit removal, the fake gateway emulator is publicly accessible.

### 20.4 Webhook Security

Inbound webhook HMAC verification is implemented in `WebhookController`. The
`payments.webhook.secret` property value is the HMAC key. Signature mismatch returns
400. This is a correct implementation. Replay protection via `BusinessEffectDedupService`
is also applied.

### 20.5 PII and Data Security

- `customers` table: `email`, `phone`, and address fields are marked with
  `@Convert(converter = EncryptedStringConverter.class)` — field-level encryption
  at JPA layer using AES (key from `app.crypto.secret`).
- `merchant_api_keys.hashed_key` — API key stored as bcrypt/SHA hash, never in plaintext.
- No explicit secret rotation management or key versioning for encryption keys.

### 20.6 Tenant Isolation

- **RLS (4 tables):** `customers`, `subscriptions_v2`, `invoices`, `payment_intents_v2`.
- **Application-layer (all other tables):** Service methods manually verify merchant ownership.
- **Sandbox isolation:** `merchant_modes` table enforces LIVE vs. SANDBOX mode at merchant
  level; `MerchantModeService` blocks live operations in sandbox mode.

### 20.7 Security Gaps

| Gap | Severity | Evidence |
|---|---|---|
| Gateway emulator reachable without guard | High | `/gateway/**` permitAll in SecurityConfig |
| API key scope enforcement inconsistent | Medium | api_keys exist but not consistently used for method-level auth |
| Encryption key rotation not modeled | Medium | Single AES key, no versioning |
| Token blacklist in-memory only | Medium | Blacklisted tokens not shared across JVM instances |
| JWT secret not validated for minimum entropy | Low | No length/entropy check at startup |

---

## 21. Observability

### 21.1 Structured Logging

`logback-spring.xml` — JSON line-per-event via Logstash Logback Encoder.
Every log event includes:
- `@timestamp`, `level`, `logger`, `thread`, `message`
- MDC fields: `requestId` (UUID), `requestPath`, `httpStatus`, `latencyMs`
- `correlationId` — propagated via `CorrelationIdFilter`
- `stack_trace` — only on exception

`MdcUtil` — utility for programmatic MDC enrichment in service methods.
`StructuredLogFields` — constants for MDC field names.

### 21.2 Metrics

Micrometer with Prometheus registry (`micrometer-registry-prometheus`):
- `FinancialMetrics` — domain-specific counters/gauges (payment captured, refund issued,
  dispute opened, revenue recognized, dunning retry, outbox processed, DLQ depth).
- `MetricsTagFactory` — consistent tag builder for merchant_id, currency, status.
- Spring Boot Actuator auto-exposes `/actuator/metrics`, `/actuator/prometheus`.

### 21.3 Health Checks

Spring Boot Actuator `/actuator/health` (public) + custom indicators:
- `OutboxLagHealthIndicator` — `DOWN` when oldest NEW outbox event > threshold.
- `DlqDepthHealthIndicator` — `DOWN` when DLQ row count > threshold.
- `ProjectionLagHealthIndicator` — `DOWN` when projection last_updated > threshold.
- `RedisHealthIndicator` — `DOWN` when Redis is unreachable.
- `DeepHealthController` — composite report with per-domain health summary.

### 21.4 SLO Tracking

`SloStatusService` — tracks per-domain SLO breach status:
- Domains: payments, outbox, webhooks, recon, risk, dunning.
- `SloDefinition` — configurable target (latency, error rate, lag).
- `SloStatusEntry` — current breach state.
- **Gap:** SLO configuration is in-code, not externalized to a policy file.
- **Gap:** SLO breach does not auto-trigger alerting — requires external monitoring polling.

### 21.5 Request Tracing

`CorrelationIdFilter` — reads `X-Correlation-Id` header (or generates UUID) and sets in MDC.
`RequestIdFilter` — generates per-request UUID `X-Request-Id`, sets in MDC + response header.
`ApiVersionFilter` — injects API version into MDC.

**No distributed tracing (OpenTelemetry/Zipkin/Jaeger)** is configured. All tracing is
log-level via correlation IDs.

### 21.6 Operational Diagnosability

- `SchedulerOpsController` — query scheduler execution history and health.
- `RepairAdminController` — trigger repair actions on-demand.
- `DomainEventController` — replay events and validate invariants.
- `DedupAdminController` — inspect dedup fingerprint state.
- `ReconAdminController` — inspect recon mismatches and trigger recon runs.
- `RiskAdminController` — inspect risk decisions, posture, and review queue.
- Ops timeline (`reporting.ops.timeline`) — chronological event log per aggregate.


---

## 22. Failure Modes

### 22.1 Database Outage

**Scenario:** PostgreSQL becomes unreachable.

**Impact:** Complete service outage. Spring `@Transactional` methods will fail with
`CannotAcquireLockException` or `JdbcException`. HikariCP will exhaust its connection
pool (default 20 connections in prod) and return `SQLTransientConnectionException`.

**Detection:** `OutboxLagHealthIndicator`, `ProjectionLagHealthIndicator` will flip
`DOWN`. `/actuator/health` will report `DOWN`.

**Gap:** No read-replica fallback for read-heavy analytics/reporting endpoints.
No circuit breaker (`Resilience4j`) wrapping repository calls.

---

### 22.2 Partial Write Failure (Payment Capture)

**Scenario:** Transaction partially succeeds — payment intent is marked SUCCEEDED but
the ledger posting fails mid-transaction.

**Mitigation:** Both payment update and ledger insert occur within the same
`@Transactional` boundary. If ledger insertion throws, the entire transaction rolls back.
The payment intent remains in its pre-capture state.

**Residual risk:** Revenue schedule generation is non-blocking (caught exception).
A schedule generation failure after capture succeeds leaves the payment captured but
without a revenue recognition schedule. `RevenueRecognitionDriftChecker` (integrity
check) would detect this drift in the next integrity run.

---

### 22.3 Duplicate Payment Request

**Scenario:** Client sends the same payment confirm twice (network retry) with the same
`Idempotency-Key`.

**Mitigation:** `IdempotencyFilter` returns the cached response on second request.
If the first request is still PROCESSING, returns 409. `BusinessEffectDedupService`
provides a second dedup layer in outbox handlers.

**Gap:** If Redis is down during the first request and the DB idempotency key write
also fails (e.g., DB outage), neither dedup layer fires on the retry.

---

### 22.4 Stale Outbox Retry (Double-Dispatch)

**Scenario:** Outbox event is marked PROCESSING, JVM restarts before DONE/FAILED
transition, event is reclaimed by `OutboxLeaseRecoveryService` and dispatched again.

**Mitigation:**
- `OutboxLeaseRecoveryService` resets events with expired `lease_expires_at` to NEW.
- `BusinessEffectDedupService` fingerprint in each handler prevents double-posting
  of the business effect.
- Handlers are expected to be idempotent on replay.

**Gap:** Handlers that do not call `BusinessEffectDedupService` could double-post.
Handler idempotency is a convention, not enforced by the framework at compile time.

---

### 22.5 Scheduler Overlap (Multiple JVM Instances)

**Scenario:** Two instances of the service are deployed and both schedulers fire at
the same instant.

**Mitigation:** `SchedulerLockService.tryAcquireAdvisoryLock()` uses
`pg_try_advisory_xact_lock()` — PostgreSQL session-scoped advisory lock. Only one
JVM acquires the lock; the other skips the run.

**Gap:** If the lock is acquired but the executing JVM dies during the job, the advisory
lock is held by the now-dead session. PostgreSQL releases advisory locks when the session
disconnects, so the lock will be released on next HikariCP connection eviction.
This is safe by design.

---

### 22.6 Redis Unavailability

**Scenario:** Redis becomes unreachable.

**Impact by subsystem:**
- **Rate limiting:** Switches to permissive mode (no enforcement). Burst traffic passes.
- **Idempotency:** Falls back to DB-only. Slower but correct.
- **Distributed locks:** Falls back to PostgreSQL advisory locks. No feature loss.
- **Fencing tokens:** INCR unavailable. `FencingTokenService` must handle failure.
  If it throws, the calling service method must handle gracefully or allow the operation
  without a fencing check — this is a potential stale-writer risk.
- **Caches:** Projection, routing, search caches miss. DB fallback. Performance degrades.

---

### 22.7 Inconsistent State Transitions

**Scenario:** Subscription transitions to ACTIVE while dunning attempt is still PROCESSING
(two threads racing).

**Mitigation:** `SubscriptionV2.version` (JPA `@Version`) provides optimistic locking.
Concurrent update causes `OptimisticLockingFailureException`, caught by
`OptimisticRetryTemplate`.

**Gap:** If both threads read the same stale version before either writes, one will
succeed and the other will retry — but there is no business-rule idempotency check on
"already ACTIVE" in the retry path. The second retry may activate an already-active
subscription (no harm if activation is idempotent, but worth verifying).

---

### 22.8 External Gateway Outage (UNKNOWN Outcomes)

**Scenario:** Payment gateway times out — response never received.

**Mitigation:** `PaymentAttemptService` stores `status=UNKNOWN` and
`unknown_gateway_outcome_at` timestamp. `PaymentRecoveryService` runs periodic checks
against the gateway to reconcile unknown outcomes.

**Gap:** Recovery scheduling frequency is not explicitly documented in code comments.
If the recovery service misses a cycle, the payment remains UNKNOWN for longer than
necessary, blocking dunning/renewal decisions.


---

## 23. Scalability Limits

The following analysis is based on the implemented architecture — not benchmarks.

### 23.1 At 10 TPS (Comfortable)

The architecture handles 10 TPS trivially. HikariCP pool (20 connections) is lightly
loaded. PostgreSQL handles 10 concurrent transactions without contention. Outbox poller
drains quickly. Redis commands are sub-millisecond.

### 23.2 At 100 TPS (Functional, Minor Contention)

Payment confirm path acquires pessimistic `SELECT … FOR UPDATE` on `payment_intents_v2`
rows. At 100 TPS with diverse intent IDs, lock contention is minimal (different rows).
HikariCP pool stays healthy at 50% utilization.

**Emerging bottlenecks:**
- Idempotency filter adds 1–2 DB round trips per request. At 100 TPS: 200 idempotency
  queries/second. Redis cache hit rate is critical.
- Outbox poller batch size needs tuning. Default batch size must be ≥ event rate to
  avoid accumulating lag.
- Revenue recognition queries (JOIN invoice × schedule × ledger) start becoming
  noticeable under analytics endpoints.

### 23.3 At 1K TPS (Stress — Requires Architecture Validation)

**Likely bottlenecks:**

1. **HikariCP pool exhaustion:** Default 20-connection pool. At 1K TPS with 5ms average
   transaction duration → 5 concurrent transactions average. A spike to 50ms average
   (revenue schedule + ledger + outbox) fills the pool. Need pool size 50–100.

2. **Idempotency DB writes:** At 1K TPS: 1000 `idempotency_keys` UPSERTs/second. Redis
   cache must absorb >90% of reads to prevent DB saturation on idempotency lookup.

3. **Outbox accumulation:** Single poller thread with SKIP LOCKED at 1K events/second
   requires near-instant handler dispatch. Long-running handlers will cause lag accumulation.
   Multiple poller threads are needed (currently single `@Scheduled` thread).

4. **Ledger write amplification:** Each payment capture writes 2–4 LedgerLine rows.
   At 1K TPS: 2K–4K appends/second to `ledger_lines`. PostgreSQL can handle this but
   WAL write amplification increases significantly.

5. **PostgreSQL advisory locks:** Scheduler advisory locks are `pg_try_advisory_xact_lock`
   (transaction-scoped), released at transaction commit. Not a bottleneck at this scale.

6. **Revenue recognition scheduler:** Processes daily batches — not a per-request concern
   unless backlog accumulates due to missed cycles.

### 23.4 At 10K TPS (Cannot be Served by Current Architecture)

**Hard limits:**
- Single PostgreSQL primary (no horizontal read scaling implemented).
- Single JVM process — monolith cannot be horizontally scaled independently per domain.
- HikariCP pool at a single instance tops out at ~200 concurrent connections before
  PostgreSQL itself becomes the limiter (typical PostgreSQL max_connections = 100–200).
- `outbox_events` table with millions of NEW rows and a single poller is a known
  throughput ceiling. Partitioning readiness infrastructure exists (V67) but actual
  conversion has not been done.
- Rate limiter Redis sliding window uses ZADD + ZREMRANGEBYSCORE + ZCARD — 3 Redis
  commands per request. At 10K TPS: 30K Redis ops/second for rate limiting alone.

**Required changes for 10K TPS:**
- Outbox horizontal fan-out (multiple pollers, per-aggregate shard assignment).
- Read replica routing for analytics/reporting endpoints.
- Connection pool per-domain isolation.
- PostgreSQL table partitioning for `outbox_events`, `domain_events`, `audit_entries`.
- Circuit breakers on all external calls.

### 23.5 At 100K TPS (Outside Monolith Architecture Scope)

At 100K TPS, this architecture requires decomposition into independent deployment units
per domain (payments, billing, dunning, recon), an external message broker (Kafka/Pulsar),
and multi-primary or distributed DB (CockroachDB, Vitess, or Aurora Global).

The repository does not implement any of these. The outbox/domain-event model is a
prerequisite for migration but not a substitute for it.

### 23.6 Scaling Bottleneck Summary

| Bottleneck | Impact at | Mitigation Path |
|---|---|---|
| Single HikariCP pool | 1K TPS | Per-domain pools, pool sizing |
| Idempotency DB writes | 1K TPS | Redis cache + TTL tuning |
| Outbox single poller | 1K TPS | Multiple poller threads |
| Ledger write amplification | 1K TPS | Write batching |
| PostgreSQL single primary | 10K TPS | Read replicas + CQRS |
| outbox_events table scan | 10K TPS | Table partitioning (V67 readiness) |
| Monolith JVM sizing | 10K TPS | Service decomposition |
| Scheduler noisy neighbor | 1K TPS | Isolated scheduler worker |

---

## 24. Operational Tooling

### 24.1 Local Developer Workflow

```bash
# Start local database
docker-compose up -d

# Run application (dev profile: H2 in-memory, Flyway auto-apply)
./mvnw spring-boot:run -Dspring.profiles.active=dev

# Run application with local PostgreSQL
./mvnw spring-boot:run -Dspring.profiles.active=local

# Run tests
./mvnw test
./mvnw verify  # includes integration tests

# Build production fat JAR
./mvnw clean package -DskipTests
```

### 24.2 Docker Setup

`docker-compose.yml`:
- `postgres:16` — PostgreSQL with `firstclub_db` database, `firstclub_user` credentials.
- `pgadmin4` — pgAdmin on port 5050.
- Named volumes for PostgreSQL data persistence.
- Correct `depends_on: postgres` ordering.

`Dockerfile`:
- Multi-stage: `eclipse-temurin:17-jdk-jammy` (build) → `eclipse-temurin:17-jre-jammy` (runtime).
- Non-root user (`spring:spring`) created in final image.
- HEALTHCHECK via `curl /actuator/health`.
- `EXPOSE 8080`.

### 24.3 CI/CD

`.github/workflows/build.yml`:
- Trigger: push/PR to `main`, `develop`.
- Jobs: `build` (single job).
- Steps: checkout → Java 17 setup → `mvn clean verify -B` → upload Surefire reports on failure.
- No Docker build step, no deployment step, no container registry push.
- No separate integration test job (IT tests run within `mvn verify`).

**Gaps:** No CD pipeline, no staging environment automation, no DB migration dry-run
in CI, no performance baseline comparison against `performance_baselines` table (V69).

### 24.4 Migration Operations

Flyway is configured with `spring.flyway.enabled=true` in all profiles. Migrations apply
at startup. `spring.flyway.baseline-on-migrate=false` (default) — new empty database
must start from V1.

**Operational risk:** V51 contains data migration (`UPDATE` statements on potentially
large tables) — must be applied during a maintenance window on production.

### 24.5 Load Tests

`load-tests/k6/` — 5 k6 load test scripts:
- Plan catalogue, subscription creation, payment flow, dunning simulation, recon scenarios.
- Used for local performance profiling; not wired into CI pipeline.
- `performance_baselines` table (V69) provides a DB-side registry for test execution
  outcomes, but comparison logic must be implemented separately.

### 24.6 Admin and Repair Operations

All admin endpoints require `ADMIN` role:
- `RepairAdminController` — 7 repair actions triggerable via API.
- `IntegrityCheckController` — trigger and inspect integrity scan runs.
- `ReconAdminController` — run recon, inspect mismatches.
- `SchedulerOpsController` — inspect scheduler history and health.
- `DomainEventController` — replay and validate event windows.
- `RiskAdminController` — manage rules, view decisions, resolve reviews.


---

## 25. Architecture Risks

Ranked by severity (Critical → High → Medium → Low):

### CRITICAL

**Risk 1: Null IP/Device Context in Payment Risk Evaluation**
- `PaymentIntentService.confirm()` passes `ip=null, deviceId=null` to `RiskDecisionService`.
- `IpVelocityEvaluator` and `DeviceReuseEvaluator` are non-functional at the payment gate.
- Only user-velocity and blocklist checks operate.
- A high-velocity attacker with a fresh device/IP is not blocked.
- Evidence: `PaymentIntentService` service class, `RiskContext` construction.

**Risk 2: Gateway Emulator Publicly Exposed**
- `/gateway/**` is `permitAll` in `SecurityConfig`.
- `GatewayController` accepts OTP-less payment charges in development mode.
- If deployed to production without configuration change, any caller can simulate
  payment success/failure on the live stack.
- Evidence: `SecurityConfig` permit-all rule, `GatewayController`.

### HIGH

**Risk 3: Rate Limiting Fails Open During Redis Outage**
- When Redis is unavailable, `RateLimitInterceptor` falls back to permissive mode.
- All traffic is allowed during the degraded window regardless of configured limits.
- A Redis outage (even brief) is an open burst-traffic window.
- Evidence: `RedisAvailabilityServiceImpl`, `RateLimitService` fallback path.

**Risk 4: RLS Coverage Gaps (90+ Unprotected Tables)**
- RLS is enabled on only 4 tables. Application-layer ownership checks cover the rest.
- A single service method that forgets a merchant ownership check leaks cross-tenant data.
- Evidence: V67 migration — only `customers`, `subscriptions_v2`, `invoices`,
  `payment_intents_v2` have RLS.

**Risk 5: In-Memory JWT Blacklist Not Shared Across Instances**
- Token blacklist is stored in the `membership.filter` package (in-memory).
- A multi-instance deployment does not share the blacklist.
- A revoked token continues to be accepted by instances that haven't seen the revoke event.
- Evidence: `JwtAuthenticationFilter` + `membership.filter` package.

**Risk 6: Revenue Schedule Generation Failure Not Automatically Repaired**
- Revenue schedule generation after payment capture is caught and non-blocking.
- A generation failure leaves the payment captured without a schedule.
- `RevenueRecognitionDriftChecker` detects but does not auto-repair.
- An operator must manually trigger `RevenueScheduleRegenerateAction`.
- Evidence: `PaymentIntentServiceImpl`, `RevenueCatchUpServiceImpl`.

### MEDIUM

**Risk 7: Statement Import Line-Level Gap**
- Reconciliation Layer 3 can only compare aggregate totals against external statements.
- Individual transaction-level discrepancies between gateway statement and internal records
  cannot be automatically detected.
- Evidence: `ExternalStatementImport` entity (no line table), `StatementImportService`.

**Risk 8: Dual Dunning Scheduler Coexistence**
- Both `DunningScheduler` (v1) and `DunningSchedulerV2` exist and are scheduled.
- Both hold advisory locks on different keys. If both run against the same subscription,
  double-retry could occur.
- Evidence: `dunning.scheduler` package — two `@Scheduled` beans.

**Risk 9: Fencing Token Not Universally Enforced**
- `FenceAwareUpdater` exists but is opt-in at call sites.
- Service methods that update `invoices`, `payment_intents_v2`, `subscriptions_v2` without
  explicit fencing bypass the stale-writer protection.
- Evidence: `FenceAwareUpdater` usage is limited to specific service methods.

### LOW

**Risk 10: AES Encryption Key Not Version-Tracked**
- `EncryptedStringConverter` uses a single AES key from `app.crypto.secret`.
- No key rotation mechanism or ciphertext versioning.
- A key rotation requires re-encrypting all PII fields — no tooling exists for this.

**Risk 11: Event Schema Drift Not Validated in CI**
- `DomainEventSchemaRegistry` tracks event type → schema version.
- No CI step validates backward compatibility when event schemas change.
- Breaking schema changes can silently break replay and projection fan-out.
- Evidence: V59 schema versioning, `.github/workflows/build.yml` (no schema check step).

---

## 26. Production Readiness

### What Is Production-Ready

| Component | Status | Evidence |
|---|---|---|
| Double-entry ledger with DB immutability | Production-ready | V56 triggers + `ImmutableLedgerGuard` + `LedgerPostingPolicy` |
| Transactional outbox with DLQ and lease | Production-ready | `outbox.*` package, V11/V47/V58 |
| Idempotency (API + checkpoint) | Production-ready | `IdempotencyFilter`, V35/V51 |
| Distributed locks with fencing | Production-ready | `platform.lock.*`, V52 |
| Rate limiting (Redis + DB fallback) | Production-ready (except fail-open) | `RedisSlidingWindowRateLimiter` |
| Payment capacity constraints | Production-ready | V55 DB CHECK + `PaymentCapacityInvariantService` |
| Dunning policy engine | Production-ready | `DunningServiceV2Impl`, `FailureCodeClassifier` |
| Reconciliation pipeline | Production-ready (aggregate-level) | `AdvancedReconciliationService` |
| Revenue recognition with guards | Production-ready | V61 guards, `RevenueRecognitionGuard` |
| Structured logging + metrics | Production-ready | `logback-spring.xml`, `FinancialMetrics` |
| Health checks + SLO monitoring | Production-ready | `DeepHealthController`, SLO indicators |
| Webhook delivery with retry + dedup | Production-ready | V48 hardening, `BusinessEffectDedupService` |

### What Is Prototype/Incomplete

| Component | Status | Notes |
|---|---|---|
| Risk context in payment confirm | Prototype | IP/device null — evaluators neutered |
| Statement import line-level recon | Partial | Aggregate totals only |
| JWT token blacklist (multi-instance) | Prototype | In-memory; not shared |
| Encryption key rotation | Not implemented | No versioning or tooling |
| CD pipeline | Not implemented | CI only; no deployment |
| Distributed tracing | Not implemented | Log-level correlation IDs only |
| Entitlement model | Not implemented | No subscription→feature binding |
| Multi-book ledger | Not implemented | Single chart of accounts |

### What Requires Hardening Before Real Traffic

1. Fix null IP/device context in payment confirm risk evaluation.
2. Guard or remove `GatewayController` in non-dev environments.
3. Replace in-memory JWT blacklist with Redis-backed shared blacklist.
4. Expand RLS to high-risk tables: `payments`, `refunds_v2`, `disputes`, `dunning_attempts`.
5. Add rate-limit enforcement even in Redis-down mode (DB-based sliding window fallback).
6. Add CI step for Flyway migration chain dry-run (`flyway info` on clean DB).
7. Add event schema compatibility check in CI.
8. Document and enforce that all outbox handlers must call `BusinessEffectDedupService`.

---

## 27. Hardening Roadmap

### Immediate Fixes (Sprint 0)

1. **Fix null risk context:** Propagate client IP from `HttpServletRequest` and device ID
   from request header into `RiskContext` in `PaymentIntentService.confirm()`.
   *Blocks: IpVelocityEvaluator, DeviceReuseEvaluator are currently non-functional.*

2. **Guard gateway emulator:** Add `@ConditionalOnProperty(name="app.gateway.emulator.enabled")`
   to `GatewayController` bean, defaulting to `false`. Explicitly set to `true` in
   `application-dev.properties` only.

3. **Redis-backed JWT blacklist:** Replace in-memory blacklist with Redis SET with TTL
   matching token expiry. All JVM instances share the revocation state.

4. **Dual dunning scheduler audit:** Verify `DunningScheduler` (v1) and `DunningSchedulerV2`
   do not claim the same subscriptions. Remove or disable v1 scheduler if v2 is complete.

### Near-Term Architecture Improvements (Q1)

5. **Expand RLS coverage:** Enable RLS on `payments`, `refunds_v2`, `disputes`,
   `dunning_attempts`, `invoices` (already done), `subscription_schedules`.

6. **Revenue schedule repair trigger:** When revenue schedule generation fails after capture
   (caught exception), immediately enqueue a repair task (outbox event or `idempotency_checkpoint`)
   rather than relying on drift checker to detect it.

7. **Rate limit DB fallback enforcement:** Implement a DB-based token-bucket fallback for
   `RateLimitService` that is enforcing (not permissive) when Redis is unavailable.

8. **Event schema CI check:** Add Maven plugin step to `build.yml` that validates registered
   event schemas for backward compatibility before merge.

9. **Fencing token call-site audit:** Audit all service methods updating fenced entities
   (`invoices`, `subscriptions_v2`, `payment_intents_v2`) to verify `FenceAwareUpdater` usage.

### Medium-Term Scale/Resilience Improvements (Q2–Q3)

10. **Outbox horizontal fan-out:** Shard outbox pollers by `aggregate_type` or `merchant_id`
    to enable concurrent processing without SKIP LOCKED contention.

11. **Table partitioning execution:** Complete the V67 partition infrastructure by running
    the DBA conversion of `outbox_events`, `domain_events`, `audit_entries` to
    `PARTITION BY RANGE (created_at)`.

12. **Read replica routing:** Route analytics/reporting/recon queries to a PostgreSQL
    read replica. Implement `AbstractRoutingDataSource` for CQRS separation.

13. **Circuit breakers:** Wrap gateway adapter calls in `Resilience4j CircuitBreaker`.
    Add bulkheads for outbox polling to prevent cascading failure under handler saturation.

14. **Connection pool per-domain:** Consider HikariCP pool isolation for write (payment/billing)
    vs. read (reporting/audit) paths.

### Operational Hardening (Ongoing)

15. **AES key rotation tooling:** Implement a key version field in encrypted columns.
    Build a migration job that re-encrypts with the new key version transparently.

16. **Distributed tracing:** Instrument with OpenTelemetry SDK. Export traces to Jaeger
    or Tempo. Prioritize payment confirm and dunning retry spans.

17. **SLO alerting:** Wire `SloStatusService` breach events to PagerDuty/Slack via
    outbox-dispatched webhook rather than relying on external polling.

18. **Performance baseline comparison in CI:** Implement a Maven step that compares
    k6 run results against `performance_baselines` (V69) and fails the build on regression.

### Security Hardening

19. **Webhook signature rotation:** Add key version to `payments.webhook.secret`
    configuration with dual-validation window during rotation.

20. **API key scope enforcement:** Consistently enforce `merchant_api_keys.mode`
    (LIVE vs. SANDBOX) and `scopes` on all endpoints that currently bypass API key auth.

21. **Input sanitization audit:** Audit `metadata_json` and `payload_json` columns
    for stored content injection risks in admin display endpoints.

### Test Strategy Improvements

22. **Mutation testing:** Add PIT mutation testing to the payment confirm and ledger
    posting paths to verify test coverage quality (not just line coverage).

23. **Contract tests:** Add consumer-driven contract tests for the gateway port
    interface to prevent breaking changes to the gateway adapter API contract.

24. **Handler idempotency framework test:** Add a base test that exercises each registered
    `OutboxEventHandler` twice (replay test) and asserts no duplicate business effect.

---

## 28. Final Assessment

### Architecture Maturity

**★★★★☆ — High-maturity for a monolith; approaching production-grade for fintech.**

The system demonstrates unusually thorough design for a Spring Boot monolith:
- Database-enforced ledger immutability (rare in open-source fintech codebases).
- Two-tier idempotency (Redis + DB) with checkpoint-level granularity.
- Fencing tokens with audit trail for stale-writer prevention.
- Policy-driven dunning with failure intelligence (STOP_EARLY, RETRY_BACKUP, etc.).
- Row-Level Security scaffolding with correct `SET LOCAL` usage.
- Structured logging with correlation/causation IDs throughout.
- Scheduler singleton enforcement across JVM instances via advisory locks.
- Healthy test suite including concurrency ITs and ledger immutability ITs.

### Engineering Quality

**Above average.** Domain packages are cohesive. Layering is clean. Entity design is
thoughtful (minor-unit capacity columns, fence tokens, immutability guards). The 69-migration
history shows intentional incremental hardening rather than big-bang schema drops.

### Realism of System Design

The design is grounded in real fintech requirements: double-entry accounting, payment
attempt/capture separation, dunning retry policies, reconciliation pipelines, and revenue
recognition. These are not academic constructs — they reflect production payment processing
concerns. The execution matches the intent in the critical financial paths.

### Strongest Implementation Areas

1. **Ledger system:** DB-trigger immutability + service-layer guard + reversal-only
   correction is a correct, production-strength implementation.
2. **Outbox architecture:** Lease heartbeat + aggregate ordering + DLQ + failure
   categorization is sophisticated and operationally sound.
3. **Payment capacity invariants:** Minor-unit DB CHECK constraints + application
   guard is belt-and-suspenders correctness.
4. **Concurrency model:** Pessimistic locking at intent level, optimistic at subscription
   level, advisory locks for schedulers — appropriate differentiation by resource contention.
5. **Idempotency:** Two-tier (Redis + DB) with checkpoint-level granularity is rare
   and highly valuable for multi-step billing operations.

### Most Critical Gaps

1. **Risk null context:** Biggest correctness bug — the risk engine is not evaluating
   two of its four evaluators on the most critical path.
2. **Gateway emulator exposure:** Biggest security risk in a production deployment.
3. **JWT blacklist not shared:** Token revocation does not work in multi-instance deployments.
4. **RLS coverage:** Only 4/90+ tenant-scoped tables have database-enforced isolation.
5. **Statement import line-level:** Reconciliation cannot do transaction-level matching.

### Next Best Actions

1. Fix risk null context (1 day of effort, critical correctness).
2. Disable gateway controller in non-dev profiles (30 minutes, critical security).
3. Redis-backed JWT blacklist (1 day, critical security for multi-instance).
4. RLS expansion to payments/refunds/disputes (1 day, high security).
5. Revenue schedule repair trigger on failure (2 days, high correctness).
6. Outbox handler idempotency framework test (1 day, correctness assurance).
7. CI Flyway migration chain dry-run (4 hours, operational safety).

The system is ready for controlled production use with a disciplined team that understands
its current limitations. The hardening roadmap items above represent the gap between
"carefully operated" and "production-resilient."

---

*End of architecture audit. Document version: 2026-03-13.*
