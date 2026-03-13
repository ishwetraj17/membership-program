# FirstClub Membership Program - Complete Repository Analysis

## Overview
This document summarizes a complete analysis of the FirstClub Membership Program repository, including all 753 Java source files and 69 database migrations.

## Report Location
Full detailed report: `REPOSITORY_ANALYSIS.txt` (2,806 lines)

## Quick Summary

### 1. Java Files: 753 Total
All Java files are listed in `REPOSITORY_ANALYSIS.txt` organized by package structure.

### 2. Database Migrations: 69 Total
Migration files from `src/main/resources/db/migration/`:
- V1-V69: Progressive schema evolution tracked with Flyway
- Key migration files with full SQL content included in the analysis report

### 3. Core Components Analyzed

#### Security Configuration
**File:** `SecurityConfig.java`
- JWT Bearer token authentication
- BCRYPT(12) password encoding (OWASP 2025 recommended)
- Stateless session management (no HTTP cookies)
- Public routes: `/api/v1/auth/*`, catalogue endpoints, Swagger
- Protected routes: require valid authentication
- H2 console: only in dev profile

#### Transactional Outbox Pattern
**Files:** `OutboxEvent.java`, `OutboxService.java`
- Event-driven architecture for reliable message delivery
- Status lifecycle: NEW → PROCESSING → DONE (or FAILED)
- Exponential backoff: 5, 15, 30, 60 minutes
- Max attempts: 5, then routes to Dead Letter Queue (DLQ)
- Processing leases with heartbeat recovery (5-minute TTL)
- Per-aggregate event ordering with sequences
- Schema-aware handlers with deduplication

#### Payment Intent Service
**File:** `PaymentIntentService.java`
- Creates payment intents in `REQUIRES_PAYMENT_METHOD` state
- State machine transitions:
  - REQUIRES_PAYMENT_METHOD → REQUIRES_CONFIRMATION (auto-advance)
  - REQUIRES_CONFIRMATION → PROCESSING, REQUIRES_ACTION, or FAILED
  - Terminal states: SUCCEEDED, FAILED
- Client secret generation for front-end confirmation
- Invoice association and amount tracking

#### Double-Entry Ledger System
**Files:** `LedgerEntry.java`, `LedgerService.java`
- Immutable journal entries (updatable=false on all columns)
- Two-leg minimum balance requirement (∑DEBIT = ∑CREDIT)
- Supports: PAYMENT_CAPTURED, REFUND_ISSUED, REVENUE_RECOGNIZED, SETTLEMENT
- Reversal entries with audit trail:
  - `reversalOfEntryId`: links to original entry
  - `reversalReason`: mandatory explanation
  - `postedByUserId`: optional operator ID
- Multi-currency support
- Line-level debit/credit tracking

#### Billing & Subscription Service
**File:** `BillingSubscriptionService.java`
- V2 subscription creation flow:
  1. Create subscription in PENDING status
  2. Generate first-period invoice
  3. Apply available credits
  4. Create PaymentIntent for remaining balance
  5. Auto-activate if fully covered by credits
- Subscription history tracking
- Auto-renewal scheduling with `nextRenewalAt`
- Invoice status management

#### Risk Management System
**Files:** `RiskService.java`, `RiskScoreDecayService.java`, `RiskEvent.java`
- IP blocklist enforcement (returns HTTP 403)
- Velocity checking: max 5 payment attempts per user per hour (returns 429)
- Risk event categorization:
  - IP_BLOCKED (HIGH severity)
  - VELOCITY_EXCEEDED (MEDIUM severity)
  - PAYMENT_ATTEMPT (LOW severity)
- Time-based score decay: half-life formula
  - Formula: `decayedScore = baseScore × 0.5^(ageHours / halfLifeHours)`
  - Default half-life: 72 hours
- Risk decision audit with rule firing records

#### Dunning Policy & Service
**Files:** `DunningPolicy.java`, `DunningServiceV2.java`, `DunningPolicyService.java`
- Merchant-configurable retry schedules
- Retry offsets in JSON: `[60, 360, 1440, 4320]` (minutes)
  - Example: retry after 1h, 6h, 24h, 3d
- Grace window: number of days to attempt retries
- Fallback to backup payment method
- Terminal status after exhaustion (e.g., SUSPENDED, CANCELLED)
- Policy resolution: DEFAULT policy or auto-create baseline
- Force retry capability for manual intervention

### 4. Key Database Tables (from Migrations)

**Users & Membership:**
- `users`, `user_roles`
- `membership_tiers`, `membership_plans`
- `subscriptions`, `subscription_history`

**Payments:**
- `payments`, `payment_attempts`, `payment_methods`
- `payment_intents`
- `refunds`

**Billing:**
- `invoices`, `invoice_lines`
- `discounts`, `discount_redemptions`
- `credit_notes`

**Accounting:**
- `ledger_accounts` (chart of accounts)
- `ledger_entries` (journal entries)
- `ledger_lines` (debit/credit legs)

**Event-Driven:**
- `outbox_events` (transactional outbox)
- `dead_letter_messages` (DLQ for failed events)

**Risk Management:**
- `risk_events`
- `ip_blocklist`

**Operations:**
- `dunning_attempts`, `dunning_policies`
- `audit_entries`, `feature_flags`, `job_locks`
- `integrity_check_runs`, `integrity_check_findings`

### 5. Architecture Patterns

- **Event Sourcing Lite:** Outbox pattern for reliable event delivery
- **Double-Entry Accounting:** Ledger system for financial correctness
- **Idempotency:** Client secret + gateway references for idempotent payments
- **Distributed Locking:** Platform-wide lock service with fencing tokens
- **State Machines:** Payment intent lifecycle with validation
- **Tenant Isolation:** Plain FK with service-layer enforcement
- **Eventual Consistency:** Async event handlers via outbox poller
- **Risk Scoring:** Velocity checking + IP blocklist + decay algorithms

### 6. Technology Stack

- **Framework:** Spring Boot
- **Security:** Spring Security with JWT
- **ORM:** JPA/Hibernate
- **Database:** PostgreSQL
- **Migrations:** Flyway
- **Caching:** Redis
- **Observability:** Micrometer metrics
- **Utilities:** Lombok
- **Testing:** JUnit, Testcontainers, integration tests

### 7. Report Contents

The complete `REPOSITORY_ANALYSIS.txt` file contains:

1. **Full list of all 753 Java files** with absolute paths
2. **List of all 69 migration files**
3. **Complete source code:**
   - SecurityConfig.java
   - OutboxEvent.java
   - OutboxService.java
   - PaymentIntentService.java
   - LedgerEntry.java
   - LedgerService.java
   - BillingSubscriptionService.java
   - RiskService.java
   - RiskScoreDecayService.java
   - RiskEvent.java
   - DunningServiceV2.java
   - DunningPolicyService.java
   - DunningPolicy.java
4. **Database migration SQL:**
   - V1__init_schema.sql
   - V8__ledger.sql
   - V10__dunning.sql
   - V11__outbox.sql
   - V12__risk.sql

## How to Use This Analysis

1. **Reference:** Use the Java file list to understand the codebase structure
2. **Migrations:** Follow the migration sequence to understand schema evolution
3. **Implementation Details:** Review the key service implementations for architectural patterns
4. **Auditing:** All file paths are absolute and ready for direct access

## Key Insights

- **Enterprise-Grade:** Implements sophisticated patterns (event sourcing, double-entry ledger, distributed locking)
- **Compliance-Ready:** Audit trails, immutable ledger, financial event logging
- **High-Reliability:** Outbox pattern, retry logic, dead-letter queues
- **Risk-Aware:** Multi-layered risk checks (IP blocking, velocity limiting, score decay)
- **Operator-Friendly:** Admin controllers for repairs, manual retries, feature flags

---

Generated: Complete Analysis of FirstClub Membership Program Repository
Total Files Analyzed: 753 Java files + 69 SQL migrations
Report Size: 2,806 lines of detailed documentation
