# 🚀 FirstClub Membership Program

> **Production-Ready Membership Management System**  
> **Architected & Developed by: Shwet Raj**

[![Java](https://img.shields.io/badge/Java-17-orange?style=for-the-badge&logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.3-brightgreen?style=for-the-badge&logo=spring)](https://spring.io/projects/spring-boot)
[![Maven](https://img.shields.io/badge/Maven-3.6+-blue?style=for-the-badge&logo=apachemaven)](https://maven.apache.org/)
[![H2 Database](https://img.shields.io/badge/Database-H2-lightblue?style=for-the-badge)](http://www.h2database.com/)
[![Swagger](https://img.shields.io/badge/API-Swagger-green?style=for-the-badge&logo=swagger)](https://swagger.io/)

## 📋 **Executive Summary**

A comprehensive, enterprise-grade membership management system designed specifically for the Indian market. Features a sophisticated 3-tier membership architecture with progressive benefits, flexible subscription models, and real-time business analytics — built on a full double-entry ledger, payment gateway simulation, reconciliation engine, and immutable domain event log.

**🎯 Key Achievement**: Built a production-ready fintech platform with 896 tests that handles complex subscription lifecycles, payment processing, ledger accounting, nightly reconciliation, and domain event replay — all with zero test failures.

---

## 📚 Documentation Index

> **For a senior engineer:** Start with [Architecture Overview](docs/architecture/01-system-overview.md), then [Write Paths](docs/architecture/03-write-paths.md), then [Ledger Model](docs/accounting/01-ledger-model.md). You will understand the full system in under 5 minutes.

### Architecture

| Doc | Summary |
|---|---|
| [01 System Overview](docs/architecture/01-system-overview.md) | Entity model, tech stack, consistency guarantees, honest limits |
| [02 Bounded Contexts](docs/architecture/02-bounded-contexts.md) | All 20 modules: purpose, writes, reads, dependencies |
| [03 Write Paths](docs/architecture/03-write-paths.md) | Every state-changing flow from API to DB to outbox |
| [04 Read Paths](docs/architecture/04-read-paths.md) | Transactional reads, projections, cache strategy |
| [05 Sync vs Async](docs/architecture/05-sync-vs-async.md) | Outbox pattern, which paths are async and why |
| [06 Concurrency Model](docs/architecture/06-concurrency-model.md) | Optimistic locks, SELECT FOR UPDATE, idempotency keys |
| [07 Failure Domains](docs/architecture/07-failure-domains.md) | What breaks under DB down, Redis down, gateway timeout |
| [08 Scaling Path](docs/architecture/08-scaling-path.md) | Current limits, Redis impact, when to add Kafka/split services |

### Accounting

| Doc | Summary |
|---|---|
| [01 Ledger Model](docs/accounting/01-ledger-model.md) | Chart of accounts, journal entries, worked example, invariants |
| [02 Revenue Recognition](docs/accounting/02-revenue-recognition.md) | ASC 606/IFRS 15, schedule generation, nightly posting |
| [03 Refunds and Disputes](docs/accounting/03-refunds-disputes-accounting.md) | Journal entries for refunds, chargebacks, dispute reserve |
| [04 Reconciliation Layers](docs/accounting/04-reconciliation-layers.md) | 4-layer recon model, mismatch types, SQL patterns |

### Operations

| Doc | Summary |
|---|---|
| [01 Reconciliation Playbook](docs/operations/01-reconciliation-playbook.md) | How to read recon reports, investigate mismatches, escalate |
| [02 DLQ Retry Runbook](docs/operations/02-dlq-retry-runbook.md) | Inspect and replay failed outbox events |
| [03 Risk Review Flow](docs/operations/03-risk-review-flow.md) | Velocity limits, IP blocks, daily review checklist |
| [04 Incident Response](docs/operations/04-incident-response.md) | P0–P3 severity, response steps, post-incident checklist |
| [05 Manual Repair Actions](docs/operations/05-manual-repair-actions.md) | Every repair action: API, SQL, safety preconditions |
| [06 Data Rebuild Playbook](docs/operations/06-data-rebuild-playbook.md) | Rebuild projections, snapshots, schedules from source tables |

### API

| Doc | Summary |
|---|---|
| [01 Idempotency Model](docs/api/01-idempotency-model.md) | 3-layer idempotency: DB + Redis cache + in-flight lock |
| [02 Webhook Contracts](docs/api/02-webhook-contracts.md) | Event types, payload schema, HMAC signature, retry policy |
| [03 Error Model](docs/api/03-error-model.md) | RFC 7807 error shape, full error code catalogue |
| [04 Auth and API Key Model](docs/api/04-auth-and-api-key-model.md) | JWT, API keys, roles, scopes, rate limiting |

### Performance

| Doc | Summary |
|---|---|
| [01 Bottlenecks](docs/performance/01-bottlenecks.md) | Known bottlenecks with thresholds and planned fixes |
| [02 Redis Usage](docs/performance/02-redis-usage.md) | All 30+ Redis key patterns with TTLs and fallbacks |
| [03 Hot Paths](docs/performance/03-hot-paths.md) | Payment confirmation, subscription create, and dunning flows |
| [04 Load Test Notes](docs/performance/04-load-test-notes.md) | 6 load test scenarios with success criteria |

---

## How Money Flows

1. Merchant creates a subscription for a customer → `subscriptions_v2` row written, `invoices_v2` draft generated
2. Invoice is finalized → amount locked, `INVOICE_FINALIZED` event emitted via outbox
3. Payment intent created against the invoice → `payment_intents_v2` row in `INITIATED`
4. Gateway processes payment → callback arrives → payment captured in one ACID transaction:
   - `payment_intents_v2` → `COMPLETED`
   - `invoices_v2` → `PAID`
   - `ledger_entries`: `DR ACCOUNTS_RECEIVABLE / CR CASH`
   - `outbox_events`: `PAYMENT_CAPTURED`
5. Outbox poller delivers `PAYMENT_CAPTURED` → projections updated, webhook sent to merchant
6. Revenue recognition schedule: daily rows post `DR DEFERRED_REVENUE / CR REVENUE_RECOGNIZED` each night
7. Nightly recon at 02:10 compares invoices ↔ payments ↔ ledger ↔ settlement batches
8. Any discrepancy → `recon_mismatches` row → operator reviews in recon playbook

---

---

## 💳 **Fintech Capabilities**

### **Double-Entry Accounting Ledger**

| Account | Type | Role |
|---|---|---|
| `SUBSCRIPTION_LIABILITY` | LIABILITY | Pre-collected subscription fees |
| `REVENUE_SUBSCRIPTIONS` | REVENUE | Earned revenue on activation |
| `PG_CLEARING` | ASSET | Funds held by payment gateway |
| `BANK` | ASSET | Settled funds in bank account |
| `ACCOUNTS_RECEIVABLE` | ASSET | Outstanding invoices |
| `REFUNDS_PAYABLE` | LIABILITY | Credit notes and refunds |

Every financial event posts a balanced journal entry (DR = CR). The ledger is append-only; corrections use reversal entries.

### **Payment Gateway Simulation**

- Create `PaymentIntent` → receive a gateway `txnId`
- Simulate gateway callback: `SUCCEEDED`, `FAILED`, `REFUNDED`
- Idempotent capture: duplicate gateway callbacks are safely ignored (idempotency key)
- Risk controls: velocity limits + IP block list checked before capture

### **Invoice & Billing Engine**

- Auto-generated invoice on subscription creation (status: `PENDING`)
- Invoice transitions: `PENDING → PAID` on payment, `PENDING → VOID` on cancellation
- Credit notes for proration on mid-cycle downgrade
- Full refund lifecycle: `PENDING → APPROVED → PROCESSED`

### **Subscription Renewal & Dunning**

- Nightly renewal job: activates due subscriptions, posts ledger entries
- Dunning engine: 3-attempt retry schedule (Day 1 / Day 4 / Day 7)
- Grace-period management: `ACTIVE → PAST_DUE → SUSPENDED → CANCELLED`
- Outbox pattern: reliable domain event delivery (transactional outbox table)

### **Nightly Reconciliation & Settlement**

- **Settlement** (`02:00 UTC`): sums all captured payments → posts `DEBIT BANK / CREDIT PG_CLEARING` ledger entry
- **Reconciliation** (`02:10 UTC`): compares invoice totals (expected) vs captured payments (actual); detects 4 mismatch types:
  - `INVOICE_NO_PAYMENT` — invoice with no matching payment
  - `PAYMENT_NO_INVOICE` — orphan payment with no invoice
  - `AMOUNT_MISMATCH` — invoice amount ≠ captured amount
  - `DUPLICATE_GATEWAY_TXN` — same gateway txn ID on multiple payments
- Admin REST API: JSON report + CSV download + on-demand settlement trigger

### **Immutable Domain Event Log + Replay**

- Append-only `domain_events` table: `INVOICE_CREATED`, `PAYMENT_SUCCEEDED`, `SUBSCRIPTION_ACTIVATED`, `REFUND_ISSUED`
- Events written in the same DB transaction as the triggering operation (no dual-write risk)
- Replay endpoint (`VALIDATE_ONLY` mode): re-checks 4 accounting invariants over any time window without mutating state

### **Security & Observability**

- PII encryption: `phoneNumber` and `address` encrypted at rest with AES-256-GCM
- Structured JSON logging with `requestId` MDC propagation
- Micrometer metrics: `payment.captured`, `invoice.created`, `reconciliation.mismatches`
- Risk controls: per-user velocity limits + IP block list with `@PreAuthorize` on admin endpoints

---

-----

## 🏗️ **System Architecture & Implementation**

### **🎨 Architecture Pattern**

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Controllers   │────│    Services     │────│  Repositories   │
│   (REST Layer)  │    │ (Business Logic)│    │  (Data Access)  │
└─────────────────┘    └─────────────────┘    └─────────────────┘
         │                       │                       │
         ▼                       ▼                       ▼
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│    Swagger UI   │    │      DTOs       │    │   H2 Database   │
│ (Documentation) │    │ (Data Transfer) │    │   (In-Memory)   │
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

### **🔧 Technical Implementation**

#### **Core Technologies**

- **Java 17** - LTS release with modern language features
- **Spring Boot 3.4.3** - Enterprise framework with auto-configuration
- **Spring Data JPA** - Declarative data access with Hibernate
- **H2 Database** - In-memory database optimized for demos
- **Swagger/OpenAPI 3** - Comprehensive API documentation
- **Bean Validation** - Robust input validation with custom validators

#### **Design Patterns Implemented**

- **Repository Pattern** - Clean data access abstraction
- **Service Layer Pattern** - Business logic encapsulation
- **DTO Pattern** - API contract separation from entities
- **Builder Pattern** - Fluent object construction
- **Strategy Pattern** - Flexible pricing calculations

#### **Indian Market Optimizations**

- **Currency**: All pricing in INR with proper formatting
- **Validation**: Indian phone numbers (10 digits, starts with 6-9)
- **Geography**: 6-digit pincode validation
- **Localization**: Indian cities and states in sample data

-----

## 💼 **Business Logic Implementation**

### **🏆 3-Tier Membership System**

|Tier        |Benefits                                                                       |Pricing Strategy       |
|------------|-------------------------------------------------------------------------------|-----------------------|
|**Silver**  |5% discount, 2 monthly coupons, 5-day delivery                                 |Entry-level: ₹299/month|
|**Gold**    |10% discount, free delivery, exclusive deals, 5 monthly coupons, 3-day delivery|Premium: ₹499/month    |
|**Platinum**|15% discount, priority support, 10 monthly coupons, same-day delivery          |Ultimate: ₹799/month   |

### **💰 Dynamic Pricing Engine**

```java
// Intelligent savings calculation
Monthly Plan: No discount (base price)
Quarterly Plan: 5% savings (3 months commitment)
Yearly Plan: 15% savings (12 months commitment)

// Example: Gold Tier
Monthly: ₹499 × 1 = ₹499
Quarterly: ₹499 × 3 × 0.95 = ₹1,423 (Save ₹74)
Yearly: ₹499 × 12 × 0.85 = ₹5,099 (Save ₹889)
```

### **📊 Subscription Lifecycle Management**

- **Creation**: Instant activation with tier benefits
- **Upgrades/Downgrades**: Mid-cycle tier changes with prorated pricing
- **Renewals**: Automatic renewal system with grace periods
- **Cancellations**: Flexible cancellation with reason tracking
- **Analytics**: Real-time subscription metrics and revenue tracking

-----

## � **Running with Local PostgreSQL (Docker Compose)**

For a production-parity local environment backed by a real PostgreSQL instance:

### **Prerequisites**
- **Docker** (with the `docker compose` plugin or Docker Desktop)

### **Steps**

```bash
# 1. Start PostgreSQL + PgAdmin
bash scripts/dev-up.sh

# 2. Run the application with the local profile
bash scripts/run-local.sh
```

| Service              | URL                        | Credentials                           |
|----------------------|----------------------------|---------------------------------------|
| Application (API)    | http://localhost:8080      | —                                     |
| Swagger UI           | http://localhost:8080/swagger-ui.html | —                        |
| PgAdmin              | http://localhost:5050      | admin@firstclub.com / admin           |
| PostgreSQL (direct)  | localhost:5432             | membership_user / membership_pass     |

```bash
# Stop and remove containers (data volume preserved)
bash scripts/dev-down.sh
```

> **Note:** The local profile uses `spring.jpa.hibernate.ddl-auto=validate` with Flyway migrations,
> so the database schema is managed by Flyway — not re-created on every restart.

---

## �🚀 **Quick Start Guide**

### **⚡ Prerequisites**

- **Java 17+** ([Download](https://adoptium.net/))
- **Maven 3.6+** ([Download](https://maven.apache.org/download.cgi))
- **IDE** (VS Code, IntelliJ IDEA, or Eclipse)

### **📦 Running from Zip File (For Recruiters)**

#### **1. Extract & Navigate**

```bash
# Extract the downloaded zip file
unzip membership-program.zip
cd membership-program
```

#### **2. One-Command Run**

```bash
# Build and start the application
mvn clean install && mvn spring-boot:run
```

#### **3. Success! Open These URLs:**

- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **Database Console**: http://localhost:8080/h2-console
- **Health Check**: http://localhost:8080/api/v1/membership/health
- **Analytics**: http://localhost:8080/api/v1/membership/analytics

### **🛠️ Installation & Setup**

#### **1. Clone & Navigate**

```bash
git clone <repository-url>
cd membership-program
```

#### **2. Build the Project**

```bash
# Clean build with dependency download
mvn clean install

# Verify build success
mvn compile
```

#### **3. Run the Application**

```bash
# Start the Spring Boot application
mvn spring-boot:run

# Alternative: Run the JAR directly
java -jar target/membership-program-1.0.0.jar
```

#### **4. Verify Startup**

Look for this success message:

```
======================================================================
🚀 FirstClub Membership Program Started Successfully!
👨‍💻 Developed by: Shwet Raj
======================================================================
📊 Swagger UI: http://localhost:8080/swagger-ui.html
🔍 H2 Console: http://localhost:8080/h2-console
💚 Health: http://localhost:8080/api/v1/membership/health
📈 Analytics: http://localhost:8080/api/v1/membership/analytics
======================================================================
```

-----

## 🌐 **Application Access Points**

### **📊 API Documentation (Swagger UI)**

- **URL**: http://localhost:8080/swagger-ui.html
- **Features**: Interactive API testing, request/response examples, schema documentation
- **Usage**: Test all 15+ endpoints directly from the browser

### **🗄️ Database Management (H2 Console)**

- **URL**: http://localhost:8080/h2-console
- **Login Credentials**:
  - **JDBC URL**: `jdbc:h2:mem:membershipdb`
  - **Username**: `sa`
  - **Password**: `password`
- **Features**: Browse tables, execute SQL queries, view data relationships

### **💚 System Health Monitoring**

- **URL**: http://localhost:8080/api/v1/membership/health
- **Data**: System status, user metrics, subscription analytics, environment info

### **📈 Business Analytics Dashboard**

- **URL**: http://localhost:8080/api/v1/membership/analytics
- **Insights**: Revenue analysis, tier popularity, plan distribution, user engagement

-----

## 🧪 **API Testing Examples**

### **👤 User Management**

#### **Create a New User**

```bash
curl -X POST http://localhost:8080/api/v1/users \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Rahul Kumar",
    "email": "rahul.kumar@company.com",
    "phoneNumber": "9876543210",
    "address": "123 Tech Park",
    "city": "Bangalore",
    "state": "Karnataka",
    "pincode": "560001"
  }'
```

#### **Get User by Email**

```bash
curl -X GET http://localhost:8080/api/v1/users/email/rahul.kumar@company.com
```

### **💳 Membership Operations**

#### **View All Available Plans**

```bash
curl -X GET http://localhost:8080/api/v1/membership/plans
```

#### **Get Plans by Tier**

```bash
curl -X GET http://localhost:8080/api/v1/membership/plans/tier/GOLD
```

#### **Create a Subscription**

```bash
curl -X POST http://localhost:8080/api/v1/membership/subscriptions \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "planId": 4,
    "autoRenewal": true
  }'
```

#### **Check Active Subscription**

```bash
curl -X GET http://localhost:8080/api/v1/membership/subscriptions/user/1/active
```

### **📊 Analytics & Monitoring**

#### **System Health Check**

```bash
curl -X GET http://localhost:8080/api/v1/membership/health
```

#### **Business Analytics**

```bash
curl -X GET http://localhost:8080/api/v1/membership/analytics
```

-----

## 📁 **Project Structure & Implementation Details**

### **🏗️ Clean Architecture Implementation**

```
src/main/java/com/firstclub/
├── membership/
│   ├── MembershipApplication.java        # Spring Boot entry point
│   ├── config/                           # DatabaseConfig, SwaggerConfig
│   ├── controller/                       # Membership, Plan, Subscription, User controllers
│   ├── dto/                              # Request/response DTOs
│   ├── entity/                           # JPA entities (User, Subscription, etc.)
│   ├── exception/                        # GlobalExceptionHandler, MembershipException
│   ├── repository/                       # Spring Data JPA repositories
│   └── service/impl/                     # MembershipServiceImpl, UserServiceImpl
├── billing/                              # Invoice engine
│   ├── entity/                           # Invoice, InvoiceLineItem
│   ├── repository/                       # InvoiceRepository
│   ├── service/                          # InvoiceService
│   └── controller/                       # InvoiceController
├── payment/                              # Payment gateway simulation
│   ├── entity/                           # Payment, PaymentIntent
│   ├── repository/                       # PaymentRepository, PaymentIntentRepository
│   ├── service/                          # PaymentService
│   ├── controller/                       # PaymentController, GatewayCallbackController
│   └── risk/                             # RiskService, VelocityChecker, IpBlockService
├── ledger/                               # Double-entry accounting
│   ├── entity/                           # LedgerEntry, LedgerAccount
│   ├── repository/                       # LedgerEntryRepository, LedgerAccountRepository
│   ├── service/                          # LedgerService
│   └── controller/                       # LedgerController
├── refund/                               # Refund & credit note engine
│   ├── entity/                           # Refund
│   ├── repository/                       # RefundRepository
│   ├── service/                          # RefundService
│   └── controller/                       # RefundController
├── renewal/                              # Subscription renewal & dunning
│   ├── entity/                           # DunningAttempt
│   ├── service/                          # RenewalService, DunningService
│   └── scheduler/                        # RenewalScheduler
├── outbox/                               # Transactional outbox pattern
│   ├── entity/                           # OutboxEvent
│   ├── repository/                       # OutboxEventRepository
│   ├── service/                          # OutboxService, OutboxPoller
│   └── config/                           # DomainEventTypes
├── recon/                                # Nightly reconciliation & settlement
│   ├── entity/                           # Settlement, ReconReport, ReconMismatch, MismatchType
│   ├── repository/                       # SettlementRepository, ReconReportRepository
│   ├── dto/                              # ReconReportDTO, SettlementDTO, ReconMismatchDTO
│   ├── service/                          # ReconciliationService, SettlementService
│   ├── scheduler/                        # NightlyReconScheduler
│   └── controller/                       # ReconAdminController
└── events/                               # Immutable domain event log
    ├── entity/                           # DomainEvent
    ├── repository/                       # DomainEventRepository
    ├── dto/                              # ReplayReportDTO
    ├── service/                          # DomainEventLog, DomainEventTypes, ReplayService
    └── controller/                       # ReplayController
```

### **🎯 Key Implementation Features**

#### **Automatic Data Initialization**

- ✅ **Smart Startup**: Creates 3 tiers and 9 plans automatically
- ✅ **Sample Data**: Pre-loads realistic Indian users for testing
- ✅ **Idempotent**: Safe to restart multiple times

#### **Advanced Validation**

- ✅ **Indian Phone Numbers**: 10 digits starting with 6-9
- ✅ **Pincode Validation**: 6-digit Indian postal codes
- ✅ **Email Uniqueness**: Prevents duplicate registrations
- ✅ **Business Rules**: Prevents invalid subscription states

#### **Subscription Intelligence**

- ✅ **Lifecycle Management**: Active → Expired → Cancelled flow
- ✅ **Upgrade/Downgrade**: Seamless tier transitions
- ✅ **Auto-Renewal**: Configurable automatic renewals
- ✅ **Grace Periods**: Flexible expiration handling

#### **Real-Time Analytics**

- ✅ **Revenue Tracking**: Total revenue and per-user metrics
- ✅ **Tier Analysis**: Popularity and distribution insights
- ✅ **User Engagement**: Subscription patterns and trends
- ✅ **System Health**: Performance and availability monitoring

-----

## 📊 **API Endpoints Reference**

### **👤 User Management APIs**

|Method  |Endpoint                     |Description                           |
|--------|-----------------------------|--------------------------------------|
|`POST`  |`/api/v1/users`              |Create new user with Indian validation|
|`GET`   |`/api/v1/users/{id}`         |Retrieve user by ID                   |
|`GET`   |`/api/v1/users/email/{email}`|Find user by email address            |
|`PUT`   |`/api/v1/users/{id}`         |Update user information               |
|`DELETE`|`/api/v1/users/{id}`         |Delete user account                   |
|`GET`   |`/api/v1/users`              |List all registered users             |

### **💳 Membership & Plans APIs**

|Method|Endpoint                              |Description                                        |
|------|--------------------------------------|---------------------------------------------------|
|`GET` |`/api/v1/membership/plans`            |Get all available plans with pricing               |
|`GET` |`/api/v1/membership/plans/tier/{tier}`|Filter plans by tier (SILVER/GOLD/PLATINUM)        |
|`GET` |`/api/v1/membership/plans/type/{type}`|Filter plans by duration (MONTHLY/QUARTERLY/YEARLY)|
|`GET` |`/api/v1/membership/plans/{id}`       |Get specific plan details                          |
|`GET` |`/api/v1/membership/tiers`            |List all membership tiers with benefits            |
|`GET` |`/api/v1/membership/tiers/{name}`     |Get specific tier information                      |

### **🔄 Subscription Management APIs**

|Method|Endpoint                                               |Description                    |
|------|-------------------------------------------------------|-------------------------------|
|`POST`|`/api/v1/membership/subscriptions`                     |Create new subscription        |
|`GET` |`/api/v1/membership/subscriptions/user/{userId}`       |Get user’s subscription history|
|`GET` |`/api/v1/membership/subscriptions/user/{userId}/active`|Get current active subscription|
|`PUT` |`/api/v1/membership/subscriptions/{id}`                |Update subscription settings   |
|`POST`|`/api/v1/membership/subscriptions/{id}/cancel`         |Cancel active subscription     |
|`POST`|`/api/v1/membership/subscriptions/{id}/renew`          |Renew expired subscription     |
|`POST`|`/api/v1/membership/subscriptions/{id}/upgrade`        |Upgrade to higher tier         |
|`POST`|`/api/v1/membership/subscriptions/{id}/downgrade`      |Downgrade to lower tier        |
|`GET` |`/api/v1/membership/subscriptions`                     |Get all subscriptions (admin)  |

### **📊 Analytics & Monitoring APIs**

|Method|Endpoint                      |Description                    |
|------|------------------------------|-------------------------------|
|`GET` |`/api/v1/membership/health`   |System health and metrics      |
|`GET` |`/api/v1/membership/analytics`|Business intelligence dashboard|

### **💰 Payment APIs**

|Method|Endpoint                                   |Description                              |
|------|-------------------------------------------|-----------------------------------------|
|`POST`|`/api/v1/payments/intent`                  |Create a payment intent                  |
|`POST`|`/api/v1/payments/gateway/callback`        |Simulate gateway callback (SUCCEEDED/FAILED/REFUNDED) |
|`GET` |`/api/v1/payments/{id}`                    |Get payment details                      |

### **📄 Invoice APIs**

|Method|Endpoint                        |Description                    |
|------|--------------------------------|-------------------------------|
|`GET` |`/api/v1/invoices/{id}`         |Get invoice with line items    |
|`GET` |`/api/v1/invoices/user/{userId}`|List invoices for a user       |

### **📒 Ledger APIs**

|Method|Endpoint                        |Description                    |
|------|--------------------------------|-------------------------------|
|`GET` |`/api/v1/ledger/accounts`       |List all ledger accounts       |
|`GET` |`/api/v1/ledger/balances`       |Get current debit/credit totals|
|`GET` |`/api/v1/ledger/entries`        |List journal entries (pageable)|

### **🔄 Refund APIs**

|Method|Endpoint                        |Description                    |
|------|--------------------------------|-------------------------------|
|`POST`|`/api/v1/refunds`               |Request a refund               |
|`POST`|`/api/v1/refunds/{id}/approve`  |Approve a pending refund       |
|`GET` |`/api/v1/refunds/{id}`          |Get refund status              |

### **🔧 Admin APIs (requires ADMIN role)**

|Method|Endpoint                                   |Description                              |
|------|-------------------------------------------|-----------------------------------------|
|`GET` |`/api/v1/admin/recon/daily?date=YYYY-MM-DD`|Run / fetch reconciliation report (JSON) |
|`GET` |`/api/v1/admin/recon/daily.csv?date=YYYY-MM-DD`|Download reconciliation CSV          |
|`POST`|`/api/v1/admin/recon/settle?date=YYYY-MM-DD`|Trigger settlement for a date          |
|`POST`|`/api/v1/admin/replay?from=...&to=...&mode=VALIDATE_ONLY`|Replay domain events (read-only) |

-----

## 🎯 **Business Value Demonstration**

### **💰 Revenue Model Implementation**

```java
// Dynamic pricing with commitment incentives
Silver Monthly:    ₹299  (Base price)
Silver Quarterly:  ₹849  (₹50 savings - 5.6% off)
Silver Yearly:     ₹3,058 (₹530 savings - 14.8% off)

Gold Monthly:      ₹499  (Base price)
Gold Quarterly:    ₹1,423 (₹74 savings - 5.0% off)  
Gold Yearly:       ₹5,099 (₹889 savings - 14.8% off)

Platinum Monthly:  ₹799  (Base price)
Platinum Quarterly: ₹2,277 (₹120 savings - 5.0% off)
Platinum Yearly:   ₹8,159 (₹1,429 savings - 14.9% off)
```

### **📈 Analytics Insights**

- **Customer Lifetime Value**: Tracks revenue per user across tiers
- **Tier Migration**: Monitors upgrade/downgrade patterns
- **Retention Analysis**: Measures subscription renewal rates
- **Geographic Distribution**: Indian market penetration insights

### **🚀 Scalability Features**

- **Database Agnostic**: Easy switch from H2 to PostgreSQL/MySQL
- **Stateless Design**: Horizontal scaling ready
- **Caching Ready**: Service layer prepared for Redis integration
- **API Versioning**: Future-proof endpoint design

-----

## 🔧 **Configuration & Customization**

### **📊 Database Configuration**

```properties
# Development (Current)
spring.datasource.url=jdbc:h2:mem:membershipdb
spring.jpa.hibernate.ddl-auto=create-drop

# Production Ready
spring.datasource.url=jdbc:postgresql://localhost:5432/membershipdb
spring.jpa.hibernate.ddl-auto=validate
```

### **🎨 Pricing Customization**

Modify base pricing in `MembershipServiceImpl.java`:

```java
private BigDecimal getBasePriceForTier(Integer tierLevel) {
    switch (tierLevel) {
        case 1: return new BigDecimal("299"); // Silver
        case 2: return new BigDecimal("499"); // Gold  
        case 3: return new BigDecimal("799"); // Platinum
    }
}
```

### **🌍 Localization Support**

- Indian phone number pattern: `^[6-9]\\d{9}$`
- Pincode validation: `^\\d{6}$`
- Currency formatting: INR with proper symbols

-----

## 🚀 **Production Deployment Readiness**

### **✅ Production Features**

- **Comprehensive Logging**: Structured logging with correlation IDs
- **Health Monitoring**: Custom health checks and metrics
- **Error Handling**: Global exception handling with meaningful responses
- **Input Validation**: Robust validation with security considerations
- **Transaction Management**: Proper ACID compliance
- **Connection Pooling**: HikariCP for production performance

### **🔒 Security Considerations**

- **Input Sanitization**: Prevents injection attacks
- **Error Message Security**: No sensitive data in error responses
- **Validation Layers**: Multiple validation points
- **Authentication Ready**: Hooks for JWT/OAuth2 integration

### **📊 Monitoring & Observability**

- **Actuator Integration**: Spring Boot health endpoints
- **Custom Metrics**: Business-specific monitoring points
- **Performance Tracking**: Response time and throughput metrics
- **Error Tracking**: Comprehensive error logging and alerting

-----

## 🎯 **Technical Highlights**

### **🏆 Code Quality Achievements**

- **Clean Architecture**: Proper separation of concerns
- **SOLID Principles**: Adherence to software design principles
- **Comprehensive Testing**: Unit test foundation with realistic test data
- **Documentation**: Self-documenting code with Swagger integration
- **Error Resilience**: Graceful handling of edge cases and failures

### **💡 Innovation & Best Practices**

- **Indian Market Focus**: Localized for Indian business context
- **Progressive Benefits**: Sophisticated tier-based feature unlocking
- **Dynamic Pricing**: Intelligent savings calculation engine
- **Real-time Analytics**: Business intelligence with actionable insights
- **Developer Experience**: Easy setup, comprehensive documentation

### **🚀 Performance Optimizations**

- **Lazy Loading**: Efficient JPA relationships
- **Query Optimization**: Custom repository methods for complex queries
- **Connection Pooling**: Production-ready database connections
- **Caching Strategy**: Service layer prepared for distributed caching

-----

## 📈 **Future Roadmap & Extensibility**

### **Completed Phases ✅**

| Phase | Feature |
|---|---|
| Phase 1–7 | Core membership, 3-tier plans, subscription lifecycle, analytics |
| Phase 8 | Double-entry ledger, invoice engine, payment simulation, refund lifecycle |
| Phase 9 | Subscription renewal scheduler, dunning engine (3-attempt curve) |
| Phase 10 | Transactional outbox pattern for reliable event delivery |
| Phase 11 | Risk controls, PII encryption (AES-256-GCM), Micrometer observability |
| Phase 12 | Nightly reconciliation, settlement simulation, immutable domain event log + replay |
| Phase 13 | Architecture Explainability Layer: 24-file docs suite covering architecture, accounting, ops, API, performance |

### **Potential Next Steps**

- **Real Payment Gateway** (Razorpay / Stripe) replacing simulation layer
- **Email/SMS Notifications** (AWS SES / SNS) on subscription events
- **Redis Caching** for ledger balance reads
- **Kafka Integration** replacing in-process outbox poller
- **Multi-Currency Support** for international expansion
- **GraphQL API** for flexible client queries

-----

## 🏆 **Project Achievements**

### **✅ Technical Excellence**

- **Production-Ready Codebase** with enterprise patterns
- **Comprehensive API Design** with 15+ well-documented endpoints
- **Robust Data Model** with proper relationships and constraints
- **Indian Market Optimization** with local business context
- **Real-time Analytics** providing actionable business insights

### **💼 Business Value**

- **Revenue Optimization** through intelligent pricing strategies
- **Customer Retention** via tier-based progressive benefits
- **Operational Efficiency** with automated subscription management
- **Data-Driven Insights** for strategic decision making
- **Scalable Architecture** for future growth requirements

### **🎯 Developer Experience**

- **Easy Setup** with single-command deployment
- **Comprehensive Documentation** with examples and guides
- **Interactive Testing** via Swagger UI integration
- **Clean Code Architecture** following industry best practices
- **Extensible Design** for future feature additions

-----

## 📞 **Developer Profile**

**Shwet Raj** - Full Stack Developer & System Architect

**Expertise Areas:**

- Enterprise Java Development with Spring Boot
- RESTful API Design & Implementation
- Database Design & Optimization
- System Architecture & Scalability Planning
- Indian Market Business Logic Implementation

**This Project Demonstrates:**

- Advanced Spring Boot and JPA implementation
- Complex business logic with subscription lifecycle management
- Production-ready code with comprehensive error handling
- Indian market-specific optimizations and validations
- Real-time analytics and business intelligence capabilities

-----

## 🧪 **Automated Testing Suite**

### **JUnit / Spring Boot Test Suite**

Run all 896 unit + integration tests:

```bash
mvn test
```

**Coverage areas:**
- ✅ Subscription lifecycle (create, activate, renew, cancel, downgrade, upgrade)
- ✅ Payment intent create + gateway callback (200+ idempotency scenarios)
- ✅ Double-entry ledger (every transaction balanced)
- ✅ Invoice generation, void, credit-note
- ✅ Refund approval + ledger reversals
- ✅ Dunning retry schedule (3-attempt curve)
- ✅ Outbox reliable delivery
- ✅ Risk controls (velocity + IP block)
- ✅ Reconciliation mismatch detection (6 cases)
- ✅ Settlement idempotency (4 cases)
- ✅ Domain event log + replay invariants

**Test Results:** 896 tests, 0 failures, BUILD SUCCESS

### **Master API Test Suite**
Run comprehensive end-to-end API validation:

```bash
python3 master_api_tests.py
```

### **2-Minute Demo Script**

Run the full fintech platform walkthrough against a live local instance:

```bash
bash scripts/demo.sh
```

The demo script: starts Docker Postgres → boots the app → creates a user and Platinum subscription → simulates a payment → checks ledger balances → triggers settlement and reconciliation → validates domain events with replay.

-----

## � **Merchant Platform Support (Phase 1)**

The system has been extended into a **multi-tenant architecture** where every business unit
operates as an isolated merchant/tenant.

### **Tenant Model**

- Each merchant has a unique, immutable `merchantCode` (pattern: `^[A-Z0-9_]{2,64}$`)
- Default currency, timezone, and locale are configured per merchant
- Merchant-scoped settings control webhook delivery, settlement frequency, and dunning behaviour
- Every merchant has exactly one `MerchantSettings` record (auto-created with defaults)

### **Merchant Lifecycle**

```
PENDING → ACTIVE → SUSPENDED → ACTIVE   (re-activate)
PENDING / ACTIVE / SUSPENDED → CLOSED   (terminal)
```

Transitions are enforced by `StateMachineValidator` (entity key: `"MERCHANT"`).

### **Merchant Admin APIs (v2)**

> All endpoints require `ROLE_ADMIN` and a valid Bearer JWT.

| Method | Endpoint                                    | Description                      |
|--------|---------------------------------------------|----------------------------------|
| POST   | `/api/v2/admin/merchants`                   | Create merchant (→ PENDING)      |
| GET    | `/api/v2/admin/merchants`                   | Paginated list + status filter   |
| GET    | `/api/v2/admin/merchants/{id}`              | Get by ID                        |
| PUT    | `/api/v2/admin/merchants/{id}`              | Update mutable fields            |
| PUT    | `/api/v2/admin/merchants/{id}/status`       | Status transition                |
| POST   | `/api/v2/admin/merchants/{id}/users`        | Add user to merchant             |
| GET    | `/api/v2/admin/merchants/{id}/users`        | List merchant users              |
| DELETE | `/api/v2/admin/merchants/{id}/users/{uid}`  | Remove user (protects last owner)|

### **Package Layout**

```
com.firstclub.merchant/
├── controller/   MerchantAdminController, MerchantUserAdminController
├── dto/          Request + response DTOs
├── entity/       MerchantAccount, MerchantUser, MerchantSettings + enums
├── exception/    MerchantException (mirrors MembershipException)
├── mapper/       MerchantMapper, MerchantUserMapper (MapStruct)
├── repository/   JPA repositories
└── service/      MerchantService, MerchantUserService + implementations
```

Full documentation: [`docs/tenant-model.md`](docs/tenant-model.md)

-----

## �🎉 **Getting Started Today**

1. **Clone the repository**
2. **Run `mvn spring-boot:run`**
3. **Open http://localhost:8080/swagger-ui.html**
4. **Run automated tests: `python3 master_api_tests.py`**
5. **Run stress tests: `python3 stress_test.py`**
6. **Run extensive stress tests: `python3 user_stress_test.py`**
7. **Start testing the APIs immediately**

**Experience the power of a production-ready membership system built with modern Java technologies and optimized for the Indian market! 🚀**

-----

> **Built with ❤️ for the Indian Market** | **Enterprise Grade** | **Production Ready** | **Scalable Architecture**

*Ready to handle real-world membership management challenges with sophisticated business logic and comprehensive analytics.*