# PROJECT_ANALYSIS.md

> Last updated: 2026-03-13
> Workspace: membership-program (Spring Boot 3.4.3, Java 17, PostgreSQL)
> Scope: deep static analysis of billing, catalog, customer, dunning, events, ledger, merchant, notifications, outbox, payments, platform, recon, reporting, risk, subscription, and all database layers.

---

## 1. Executive Summary

This repository is a strong modular fintech backend focused on subscription billing and payment orchestration. It already has many advanced controls that are usually missing in early-stage systems:

- transactional outbox with lease recovery,
- append-only domain event log with replay safeguards,
- strict ledger immutability with reversal-only corrections,
- idempotency with database source-of-truth plus Redis acceleration,
- payment capacity invariants for refunds and disputes,
- policy-driven dunning with failure classification,
- advanced reconciliation mismatch taxonomy,
- projection rebuild and consistency tooling.

At the same time, there are critical gaps to close before this can reliably operate at Stripe/Juspay-grade scale in production:

- risk checks in payment-confirm path do not currently carry IP/device context,
- reconciliation timing classification currently uses a coarse boundary signal,
- statement import is aggregate-only (line-level persistence is missing),
- projection lag metric currently tracks oldest update, not freshest update,
- migration chain around integrity tables needs hardening and consolidation,
- RLS tenant context is available but appears opt-in, not guaranteed on every tenant query path.

Bottom line:

- This codebase is beyond MVP and already has meaningful platform controls.
- It is not yet fully production-hardened for multi-region, high-throughput, globally regulated payments without additional platform and data-governance work.

---

## 2. Repository Snapshot (Current State)

### 2.1 Scale Metrics

- Main Java files: 971
- Test Java files: 204
- Flyway migrations: 69 (V1-V69)
- Domain modules explicitly analyzed: 15
- Unique tables created through migrations: 94

### 2.2 Domain Footprint (main source files)

| Domain | Files |
|---|---:|
| platform | 217 |
| payments | 138 |
| billing | 61 |
| reporting | 60 |
| ledger | 51 |
| merchant | 44 |
| risk | 44 |
| recon | 43 |
| dunning | 34 |
| catalog | 29 |
| events | 23 |
| subscription | 21 |
| customer | 20 |
| outbox | 19 |
| notifications | 18 |

### 2.3 Architecture Style

- Modular monolith with domain package boundaries under `com.firstclub.*`.
- Spring transactional service layer with JPA repositories.
- PostgreSQL as primary consistency layer.
- Redis used as optional acceleration layer (idempotency, rate-limit, dedup, lock helpers, projection/timeline cache).
- Outbox and event log used for cross-domain decoupling.
- Heavy use of scheduler-driven background processing.

---

## 3. End-to-End Architecture and Runtime Behavior

### 3.1 Core Runtime Pattern

1. API/controller request enters service layer.
2. Domain service writes transactional records (for example invoice, payment attempt, dispute).
3. Domain event is appended to immutable event log.
4. Outbox event is published in same transaction.
5. Outbox poller dispatches handlers asynchronously with retries and DLQ fallback.
6. Reporting projections and timeline are updated asynchronously from domain events.
7. Integrity and repair services monitor and fix drift.

### 3.2 Key Reliability Controls Already Present

- Row-level locking and `FOR UPDATE SKIP LOCKED` in high-contention background jobs.
- Pessimistic lock flows for financial mutation endpoints (refund/dispute).
- Exactly-one-success attempt invariant in payment attempts.
- Reconciliation for unknown gateway outcomes.
- Lease heartbeats and stale lease recovery for outbox processing.
- API-level idempotency conflict detection (endpoint/body mismatch, in-flight handling).
- Fencing-token pattern available for stale-writer prevention in distributed locks.

### 3.3 Financial Correctness Posture

Strong positive signals:

- ledger side enforces double-entry balancing and immutable rows,
- refund/dispute capacity checks are explicit,
- invoice total recompute and invariant guards exist,
- reversal-only correction model is implemented.

Residual risks:

- not all financial pathways are guaranteed to be universally protected by fencing-token checks,
- some classification/lag analytics are operationally weak (details in module sections),
- migration discipline needs tightening in specific areas.

---

## 4. Domain-by-Domain Deep Analysis

Each section below includes:

- what is implemented,
- core business logic that is already enforced,
- what is not implemented or is weak,
- targeted improvements for fintech-grade scale.

---

### 4.1 Billing

#### What Is Implemented

- Invoice generation per subscription period.
- Period overlap guard for duplicate invoice-window prevention.
- Invoice line modeling (plan charge, credit application, tax, discount, proration, etc).
- Automatic credit-note application to open invoices.
- Invoice total recomputation with subtotal/discount/credit/tax/grand breakdown.
- Payment success hook that marks invoice paid and activates subscription.
- Domain event + outbox emission on invoice/payment milestones.
- Revenue schedule generation is triggered after payment success.

#### Implemented Business Logic

- Invoice creation validates period non-overlap.
- Credit application reduces invoice due amount using available credit balances.
- Invoice state transitions are validated through state machine checks.
- Invoice total invariant service computes expected total from lines and rejects mismatches.
- Paid invoice voiding is blocked without explicit refund/reversal path.
- Credit application beyond available balance is blocked.

#### What Is Missing or Limited

- No full invoice revision history with legal/immutable invoice version snapshots.
- No explicit tax jurisdiction workflow beyond current model.
- No explicit dunning-stage invoice collection strategy at invoice entity level.
- Revenue schedule generation failure is intentionally non-blocking, but delayed reconciliation strategy for that failure path is not deeply visible.

#### Fintech Improvement Priorities

1. Add immutable invoice revision model for compliance-grade auditability.
2. Add explicit retry/repair queue for revenue schedule generation failures.
3. Add formalized tax-rate effective-date and jurisdiction precedence validation suite.
4. Add invoice-level legal hold and irreversible-close semantics.

---

### 4.2 Catalog

#### What Is Implemented

- Merchant-scoped products.
- Merchant-scoped prices and price versions.
- Effective-date based pricing selection.
- Cross-entity merchant consistency checks.

#### Implemented Business Logic

- Price must belong to selected product.
- Subscription create path resolves currently effective price version.
- Duplicate/overlap prevention logic exists around price-version windows.

#### What Is Missing or Limited

- No deeply visible entitlement model attached to product/plan.
- No contract-term model for committed spend or minimum tenure.
- No explicit region/currency matrix for multi-country catalog governance.

#### Fintech Improvement Priorities

1. Add entitlement catalog and policy coupling to subscription lifecycle.
2. Add currency-region constraints and fallback pricing policy.
3. Add catalog publication workflow (draft -> approved -> live) with approvals.

---

### 4.3 Customer

#### What Is Implemented

- Merchant-scoped customer model.
- Customer notes.
- Encryption converter usage for sensitive fields.
- Merchant ownership checks in service layer.

#### Implemented Business Logic

- Merchant isolation is enforced in repository/service lookups.
- Customer identity and references are governed per merchant.
- PII-at-rest protection exists via converter-backed encryption for mapped fields.

#### What Is Missing or Limited

- No visible full KYC/KYB workflow lifecycle.
- No consent/versioned preferences model for privacy regulation workflows.
- No explicit customer merge/split audit process.

#### Fintech Improvement Priorities

1. Add KYC state machine with verification providers and risk hooks.
2. Add consent and communication preference versioning.
3. Add customer identity-link graph for fraud and support operations.

---

### 4.4 Dunning

#### What Is Implemented

- Policy-driven retry scheduling (`retryOffsetsJson`, max attempts, grace days).
- Subscription payment preference with primary/backup methods.
- Failure-code classification and strategy-based decisioning.
- Backup payment method fallback flow.
- Terminal status transition after exhaustion (suspend/cancel policy).
- Batch processing with `SKIP LOCKED` to avoid multi-node duplicate work.

#### Implemented Business Logic

- Attempts are scheduled from policy within grace window.
- Attempt processing validates subscription still `PAST_DUE` and invoice still `OPEN`.
- Failed attempts are classified and routed to decision outcomes:
  - stop early,
  - retry,
  - retry with backup,
  - exhausted.
- Successful attempt activates subscription and cancels remaining scheduled attempts.

#### What Is Missing or Limited

- No customer communication orchestration built into dunning engine itself.
- No adaptive retry optimizer based on issuer/gateway/customer cohorts.
- No explicit support-case auto-creation for high-value exhausted accounts.

#### Fintech Improvement Priorities

1. Add communication orchestration (email/SMS/WhatsApp/webhook) per dunning stage.
2. Add ML-assisted retry-timing policy selection by payment failure clusters.
3. Add automatic escalation to support ops for high-value churn-risk merchants/customers.

---

### 4.5 Events

#### What Is Implemented

- Immutable append-only `domain_events` log.
- Metadata-aware event recording (version, schema version, causation/correlation, aggregate info, merchant).
- Replay service with validation mode and scoped filters (merchant/aggregate/time).
- Replay safety policy with blocked/idempotent-only/allow classes.

#### Implemented Business Logic

- Replay service validates key invariants in selected windows:
  - duplicate invoice creation in-window,
  - orphan payment/subscription activation relationships in-window,
  - global ledger balance check.
- Replay safety blocks dangerous duplicate replays for specific event types.

#### What Is Missing or Limited

- Replay validation is window-bound and can produce false orphan findings when parent event lies outside time window.
- Projection rebuild in replay mode is still constrained (limited supported projection names).
- No visible external event bus publication contract for cross-system integration.

#### Fintech Improvement Priorities

1. Add lineage-aware replay that can fetch dependency context outside requested window.
2. Add deterministic event-schema registry and compatibility checks in CI.
3. Add external event streaming contract (for analytics/warehouse/risk systems).

---

### 4.6 Ledger

#### What Is Implemented

- Double-entry ledger model (`ledger_entries`, `ledger_lines`, chart-of-accounts).
- Centralized posting policy enforcing positive amounts and DR=CR balancing.
- Immutable ledger controls at both service and database trigger layers.
- Reversal-only correction service.
- Reversal lineage index for traceability.

#### Implemented Business Logic

- Every post validates balanced debit/credit totals.
- Reversal requires reason and cannot reverse a reversal recursively.
- Duplicate reversal of same original entry is blocked.
- Update/delete on ledger rows is blocked by trigger (`prevent_ledger_modification`).

#### What Is Missing or Limited

- No explicit period-close lock model for accounting periods.
- No explicit multi-book accounting (for statutory vs management books).
- No explicit FX revaluation and unrealized gain/loss process in ledger package.

#### Fintech Improvement Priorities

1. Add accounting period close and reopen governance.
2. Add FX revaluation jobs and reporting entries.
3. Add multi-book and dimensions (cost center, product line, jurisdiction).

---

### 4.7 Merchant

#### What Is Implemented

- Merchant onboarding and core account profile.
- Merchant settings auto-provisioned on create.
- Merchant status transitions through state machine validator.
- Merchant API key creation/revocation/listing with plaintext-once semantics.
- API key hashing, prefix lookup, mode support (sandbox/live).
- API key authenticator utility for eventual security integration.

#### Implemented Business Logic

- Merchant code uniqueness enforced.
- Merchant code immutability intent is present in service rules.
- API key authentication checks active status and hash match.
- Key usage timestamp is updated after successful auth.

#### What Is Missing or Limited

- API key authenticator utility exists, but full security-chain integration coverage is not fully visible across all endpoints.
- No visible scoped API key policy enforcement matrix per endpoint action.
- No merchant-level spend/risk operational limits beyond current mode and status controls.

#### Fintech Improvement Priorities

1. Enforce API key scopes in Spring Security authorization layer for all merchant APIs.
2. Add key-rotation policies with grace period and overlap windows.
3. Add merchant operational guardrails (velocity, payout risk limits, settlement holds).

---

### 4.8 Notifications and Webhooks

#### What Is Implemented

- Merchant webhook endpoints and delivery tables.
- HMAC SHA-256 payload signing.
- Endpoint subscription filtering.
- Delivery retry with backoff and terminal `GAVE_UP` status.
- Consecutive-failure auto-disable and accumulated-give-up disable safeguards.
- Delivery fingerprint dedup for enqueue idempotency.
- Ping endpoint flow.

#### Implemented Business Logic

- Only active subscribed endpoints receive deliveries.
- Success clears consecutive-failure counter.
- Failures increase attempt count and schedule retries.
- Terminal failures can disable unhealthy endpoints automatically.

#### What Is Missing or Limited

- No explicit webhook replay API with signed timestamp/nonces for anti-replay on receiver side.
- No dedicated webhook DLQ analytics domain (separate from outbox DLQ path).
- No endpoint-level adaptive throttling per receiver behavior.

#### Fintech Improvement Priorities

1. Add webhook signature timestamp and nonce headers with replay window checks.
2. Add webhook delivery observability dashboard and replay-by-delivery API.
3. Add per-endpoint circuit breaker and dynamic backoff.

---

### 4.9 Outbox

#### What Is Implemented

- Transactional outbox publisher joining business transaction.
- Poller batch claim and per-event isolated processing.
- Retry/backoff with capped attempts and DLQ routing.
- Processing owner and lease fields.
- Heartbeat extension for long-running handlers.
- Stale lease recovery (lease-based and legacy-time-based).

#### Implemented Business Logic

- Outbox events are persisted with business writes in same transaction.
- Processing status transitions are explicit (`NEW -> PROCESSING -> DONE/FAILED`).
- Unknown handler types are marked done to avoid infinite retry loops.
- Permanent failures are copied to `dead_letter_messages`.

#### What Is Missing or Limited

- Unknown handler currently marks event done; this avoids retries but can hide handler configuration drift unless actively monitored.
- Ordering guarantees are not globally strict; design is prioritized for throughput and fault isolation.
- No explicit poison-message quarantine workflow beyond DLQ insertion.

#### Fintech Improvement Priorities

1. Introduce explicit `HANDLER_NOT_FOUND` alerting SLA and auto-ticketing.
2. Add per-event-type processing SLA monitoring and lag dashboards.
3. Add configurable per-type retry strategy (not only shared static backoff buckets).

---

### 4.10 Payments

#### What Is Implemented

- Payment intent v2 model with lifecycle controls.
- Attempt model with rich statuses (`FAILED`, `SUCCEEDED`, `UNKNOWN`, `RECONCILED`, etc).
- Gateway routing selection and snapshot persistence.
- Confirmation flow with retryability checks from prior failures.
- Reconciliation service for unknown gateway outcomes.
- Refund v2 and dispute services with accounting side effects.
- Capacity invariant service to maintain non-negative net position.

#### Implemented Business Logic

- `SUCCEEDED` intent short-circuits idempotently on repeated confirm.
- `CANCELLED` is terminal for confirm path.
- `FAILED` can re-confirm only if last attempt is explicitly retriable.
- Exactly one successful attempt per intent is enforced in attempt service.
- Refund creation:
  - merchant ownership validation,
  - refundable-status checks,
  - idempotent fingerprint handling,
  - accounting reversal posting,
  - payment amount + minor-unit sync.
- Dispute flow:
  - active dispute uniqueness per payment,
  - capacity checks,
  - reserve accounting on open,
  - resolution accounting (won/lost),
  - flags to prevent double posting.

#### What Is Missing or Limited

- Risk context in confirm flow currently passes null IP/device, reducing effectiveness of IP/device-based rules.
- SCA/challenge orchestration depth is limited to status transition semantics.
- No explicit multi-acquirer smart failover telemetry and optimization loops visible in core path.

#### Fintech Improvement Priorities

1. Pass full runtime risk context (IP, device fingerprint, channel metadata) into confirm flow.
2. Add advanced payment retries with issuer-level smart routing and historical success models.
3. Add explicit authorization/capture separation flows with expiry management and re-auth.
4. Add stronger event-driven settlement state machine for gateway/webhook/state convergence.

---

### 4.11 Platform (Cross-Cutting)

#### What Is Implemented

- Idempotency filter with:
  - endpoint/body conflict detection,
  - in-flight handling,
  - DB source-of-truth,
  - Redis response cache and lock acceleration.
- Sliding-window Redis rate limiter via Lua.
- Distributed lock service with Lua safety and fencing token generation.
- Business-effect dedup service (Redis + DB durable tiers).
- Integrity run orchestrator and repair action registry.
- RLS tenant context configurer for PostgreSQL session variable.
- Scheduler advisory lock service using PostgreSQL advisory locks.
- Feature flags, job locks, audit entries, scheduler execution tracking.

#### Implemented Business Logic

- Idempotency key mismatch semantics are explicit and HTTP-consistent.
- Redis failures degrade safely to database for idempotency/rate-limit patterns where applicable.
- Lock release is owner-safe (Lua guarded) to prevent accidental unlock of another owner.
- Integrity checks persist run/finding records for operational auditing.

#### What Is Missing or Limited

- RLS context setter exists, but broad enforcement pattern appears opt-in instead of guaranteed at framework boundary.
- Fencing-token usage is available but not visibly mandatory in all critical write paths.
- Integrity checks are rich but require stronger automated response and rollout governance.

#### Fintech Improvement Priorities

1. Enforce tenant context middleware for all merchant-scoped transactions by default.
2. Add mandatory fencing-token verification on all stale-writer-sensitive entities.
3. Add policy engine for cross-domain guardrails (risk, retry, settlement, limits).
4. Expand integrity checks into release gates and SLO alerting.

---

### 4.12 Reconciliation (Recon)

#### What Is Implemented

- Daily reconciliation report generation.
- Layered reconciliation model:
  - payment vs ledger settlement,
  - ledger settlement vs settlement batch,
  - settlement batch vs external statement,
  - mismatch lifecycle (open/ack/resolve/ignore).
- Mismatch taxonomy with expectation and severity.
- Orphan gateway payment detection.
- Duplicate settlement batch detection.
- Settlement batch service with fee/reserve/net decomposition.
- Statement import endpoint and parser.

#### Implemented Business Logic

- Re-run for daily report uses locking strategy to reduce overwrite race risk.
- Mismatch records include type and details for operator triage.
- Critical mismatch classes are clearly identified and persisted.

#### What Is Missing or Limited

- Statement import currently parses and aggregates CSV totals but does not persist line-level statement entries, limiting forensic reconciliation.
- Timing-window classification in advanced reconciliation is coarse and should be tied to actual event timestamps, not static boundary values.
- No automated balancing journal generation for resolved mismatches.

#### Fintech Improvement Priorities

1. Persist statement lines as first-class entities for exact line matching and dispute evidence.
2. Correct boundary-classification logic to evaluate each mismatch record timestamp.
3. Add automated recon remediation workflow (suggested fix and reversible accounting entries).
4. Add recon SLA metrics by merchant/gateway/date.

---

### 4.13 Reporting and Projections

#### What Is Implemented

- Event-driven projection updates for multiple read models.
- Projection rebuild service for full regeneration.
- Consistency checker endpoints.
- Projection lag monitoring endpoints.
- Ops timeline with correlation-based tracing.
- Redis cache layer for projections and timeline hot reads.

#### Implemented Business Logic

- Listener updates multiple projections asynchronously per domain event.
- Projection rebuild supports both event replay and source-table rebuild paths.
- Timeline dedup and uniqueness protections are present.

#### What Is Missing or Limited

- Lag monitor currently derives lag from oldest updated row, which overstates staleness and is not ideal for freshness checks.
- Full rebuild operations use broad scans and can be expensive at large event volumes.
- Projection failure handling logs errors but stronger replay orchestration is still desirable.

#### Fintech Improvement Priorities

1. Track freshness from latest update timestamp and per-projection watermark.
2. Add checkpointed replay/backfill for large-scale projection rebuilds.
3. Add projection dead-letter and auto-replay for failed update operations.

---

### 4.14 Risk

#### What Is Implemented

- Baseline risk checks (`RiskService`) with blocklist and velocity recording.
- Rule-engine (`RiskRuleService`) with pluggable evaluators.
- Decision service where strongest action wins (`BLOCK > REVIEW > CHALLENGE > ALLOW`).
- Manual review queue with assignment and controlled transitions.
- SLA and escalation services.
- Risk score decay service and explainability endpoint.
- Merchant posture summary endpoint.

#### Implemented Business Logic

- Matched rules contribute additive score.
- REVIEW decision auto-creates manual review case.
- Manual review transitions and terminal-state protections are enforced.
- Scheduler escalates overdue cases and refreshes decayed scores.

#### What Is Missing or Limited

- Payment confirm path currently builds `RiskContext` with null IP/device, reducing practical impact of `IP_VELOCITY_LAST_10_MIN` and `DEVICE_REUSE` evaluators in core payment flow.
- Rule conflict governance and simulation tooling are not yet visible.
- No case-routing optimization by analyst skill/queue priority.

#### Fintech Improvement Priorities

1. Feed complete request telemetry (IP, device, fingerprint, geography) into risk context.
2. Add rule simulation/sandbox before production rollout.
3. Add analyst queue prioritization and auto-assignment.
4. Add longitudinal fraud graph features and chargeback-feedback loops.

---

### 4.15 Subscription

#### What Is Implemented

- Subscription v2 with merchant/customer/product/price consistency checks.
- Initial state derivation (`TRIALING` vs `INCOMPLETE`).
- Duplicate active-subscription guard per merchant-customer-product.
- State machine-validated pause/resume/cancel transitions.
- `cancelAtPeriodEnd` semantics.
- Schedule table for future subscription operations.

#### Implemented Business Logic

- Cross-merchant contamination is blocked at load time.
- Transition guards enforce legal lifecycle moves.
- Terminal states are protected from invalid mutate flows.

#### What Is Missing or Limited

- No explicit entitlements and feature-access binding shown in subscription core.
- No visible seat-based quantity management or usage-rated billing integration.
- No deeply visible pause-proration/restart-proration strategy in core section.

#### Fintech Improvement Priorities

1. Add entitlement orchestration tightly coupled to subscription status transitions.
2. Add seat and usage dimensions with metering ingestion.
3. Add configurable proration matrices for pause/resume/upgrade/downgrade combinations.

---

## 5. Database and Migration Analysis (V1-V69)

### 5.1 Migration Evolution Overview

High-level sequence:

- V1-V9: foundational membership, payments, billing, ledger, basic subscription history.
- V10-V20: dunning, outbox, risk, recon, domain events, merchant/customer/catalog, v2 subscriptions/payment methods/intents.
- V21-V33: routing, billing enhancements, tax profiles, revenue recognition, refunds/disputes, policy dunning, webhooks, advanced recon, risk engine, merchant auth/modes.
- V34-V49: ops/flags, rate limits, dedup, integrity and repair, projections/timeline, hardening for revenue/refund/dispute/outbox/webhooks, support tracking.
- V50-V59: platform contracts, idempotency hardening, lock fencing audit, scheduler tracking, payment unknown outcomes, payment capacity, ledger immutability, integrity audit tables, outbox ordering/leasing, event schema versioning.
- V60-V69: recon expectation/FX fields, revenue guards, dunning failure intelligence, invoice correctness guards, risk score decay fields, additional projections, DB hardening/index/RLS/partition helper, financial audit and API versioning, elite testing infrastructure.

### 5.2 Database Objects Created

#### Core user/membership/billing/payment foundation

- users
- user_roles
- membership_tiers
- membership_plans
- subscriptions
- subscription_history
- payment_intents
- payments
- webhook_events
- dead_letter_messages
- invoices
- invoice_lines
- credit_notes
- ledger_accounts
- ledger_entries
- ledger_lines
- refunds

#### Dunning, outbox, risk, recon, events

- dunning_attempts
- outbox_events
- risk_events
- ip_blocklist
- settlements
- recon_reports
- recon_mismatches
- domain_events

#### Merchant, customer, catalog, subscription v2, payment methods

- merchant_accounts
- merchant_users
- merchant_settings
- customers
- customer_notes
- products
- prices
- price_versions
- subscriptions_v2
- subscription_schedules
- payment_methods
- payment_method_mandates

#### Routing, tax, revenue, refunds/disputes, dunning policies

- gateway_route_rules
- gateway_health
- invoice_sequences
- discounts
- discount_redemptions
- tax_profiles
- customer_tax_profiles
- revenue_recognition_schedules
- refunds_v2
- disputes
- dispute_evidence
- dunning_policies
- subscription_payment_preferences

#### Webhooks, ops, feature flags, rate limits

- merchant_webhook_endpoints
- merchant_webhook_deliveries
- feature_flags
- job_locks
- rate_limit_events

#### Projections and reporting

- customer_billing_summary_projection
- ledger_balance_snapshots
- merchant_daily_kpis_projection
- settlement_batches
- settlement_batch_items
- external_statement_imports
- risk_rules
- risk_decisions
- manual_review_cases
- subscription_status_projection
- invoice_summary_projection
- payment_summary_projection
- recon_dashboard_projection
- ops_timeline_events
- revenue_waterfall_projection
- customer_payment_summary_projection
- ledger_balance_projection
- merchant_revenue_projection

#### Platform hardening and governance

- audit_logs
- audit_entries
- business_effect_fingerprints
- idempotency_keys
- idempotency_checkpoints
- distributed_lock_audit
- scheduler_execution_history
- integrity_check_runs
- integrity_check_findings
- integrity_check_results
- repair_actions_audit
- partition_management_log

#### Merchant auth/versioning and test infrastructure

- merchant_api_keys
- merchant_modes
- merchant_api_versions
- test_execution_runs
- performance_baselines

#### Support operations

- support_cases
- support_notes

### 5.3 Database-Level Safety and Correctness Controls

Strong controls observed:

- ledger immutability trigger protection (`ledger_entries`, `ledger_lines`).
- payment capacity constraints (captured >= refunded + disputed), plus non-negative amount checks.
- invoice correctness guard migration and service-level invariant enforcement.
- outbox ordering/leasing schema evolution for safer async dispatch.
- schema/version metadata support for domain events.
- expanded audit structures for platform and financial governance.
- RLS foundation migration for tenant isolation controls.

### 5.4 Locking and Concurrency Patterns in DB Layer

- `FOR UPDATE SKIP LOCKED` for outbox and dunning/webhook batch workers.
- pessimistic lock on payment rows in refund/dispute mutation paths.
- advisory locks for scheduler singleton execution.
- upsert/locking around recon report row to reduce concurrent overwrite races.

### 5.5 Migration and Schema Risks

1. Integrity table evolution appears to include potentially conflicting definitions across versions (needs migration-chain verification from empty database and consolidation plan).
2. Statement import model is currently aggregate-level, not line-level, limiting direct DB-based reconciliation granularity.
3. RLS requires consistent application-layer context-setting discipline; without framework-level enforcement, tenant leak risk depends on service behavior.

---

## 6. Cross-Domain Financial Flow Analysis

### 6.1 Invoice-to-Cash

Implemented path:

1. Subscription period invoice created.
2. Credits/discounts/tax/proration applied and total recomputed.
3. Payment intent confirmed and routed.
4. Attempt succeeds/fails/unknown.
5. On success, invoice paid and subscription activated.
6. Ledger and revenue side effects generated.
7. Events and outbox notify downstream consumers.

Risk areas:

- degraded risk context in confirm path,
- projection staleness interpretation based on oldest updates,
- unknown outcomes rely on reconciler scheduling and operational discipline.

### 6.2 Refund and Dispute Financial Integrity

Implemented posture:

- refund and dispute opening are guarded by capacity checks,
- disputes reserve and release accounting are explicit,
- double-posting protections exist through posted flags and lock sequencing,
- payment minor-unit fields are synchronized before persist.

Risk areas:

- ensure every side-effect path uses the same invariant utility and lock order conventions,
- add formal financial reconciliation around reserve-posting and resolution-posting events.

### 6.3 Dunning-to-Churn Control

Implemented posture:

- policy-driven retries,
- failure intelligence and strategy,
- backup instrument support,
- terminal status transition.

Risk areas:

- communications and customer-experience orchestration is external to dunning core,
- no visible adaptive optimization for issuer-specific retry behavior.

### 6.4 Recon-to-Ops Resolution Loop

Implemented posture:

- mismatch detection and lifecycle endpoints,
- taxonomy and severity classification,
- orphan and duplicate detector checks.

Risk areas:

- no fully automated resolution/journaling loop,
- lack of persisted statement lines slows root-cause and audit workflows.

---

## 7. Security and Compliance Posture

### 7.1 Existing Strengths

- API idempotency conflict controls.
- HMAC signing for outgoing webhooks.
- API key hashing and mode partitioning.
- encryption support for customer sensitive fields.
- immutable event and ledger records for audit trails.
- audit tables and operational tracking artifacts.

### 7.2 Gaps to Address

1. End-to-end key management and secret rotation governance should be explicitly codified and tested.
2. RLS needs enforced-by-default context application policy.
3. No explicit compliance workflow artifacts are visible for PCI/SOC evidence automation in runtime paths.
4. Merchant API scopes should be enforced consistently at authorization boundaries.

---

## 8. Operational Observability and Resilience

### 8.1 Strong Existing Capabilities

- outbox lease visibility fields,
- scheduler execution history,
- integrity run history,
- projection consistency endpoints,
- replay and timeline support for investigations,
- rich logging around retries/failures.

### 8.2 Observability Gaps

1. Need first-class SLO dashboards per domain (payments, outbox, webhook, recon, risk decisions, dunning).
2. Need stronger alert routing for silent failure classes (for example unknown handler done-state events).
3. Need reconciliation and projection lag alert thresholds with ownership mapping.

---

## 9. What The System Does Well vs What It Does Not Yet Do

### 9.1 What It Does Well Today

- Supports full subscription-billing-payment loop with meaningful correctness controls.
- Maintains accounting-aware data integrity for refunds/disputes.
- Provides strong event/outbox/projection architecture for modular growth.
- Includes serious platform engineering (idempotency, locks, dedup, integrity, repair hooks).

### 9.2 What It Does Not Yet Fully Do

- It is not yet a complete global multi-acquirer orchestration platform with deep issuer intelligence.
- It does not yet provide line-level external statement ingestion and fully automated recon remediation.
- It does not yet enforce RLS and tenant context uniformly by construction.
- It does not yet expose comprehensive compliance automation and policy governance at enterprise scale.

---

## 10. Benchmark Readiness (Stripe, Juspay, Slice, Atlassian-like Expectations)

### 10.1 Already Competitive Areas

- modular domain decomposition,
- financial mutation invariants,
- outbox/event-driven architecture,
- replay and auditability foundation,
- ops tooling around projections, recon, integrity.

### 10.2 Not Yet at Top-Tier Maturity

- risk context completeness and model sophistication,
- global settlement and reconciliation depth,
- formalized tenant-isolation enforcement and policy-as-code controls,
- large-scale backfill/replay and data-governance automation,
- enterprise API governance and end-to-end access control matrix.

### 10.3 Practical Positioning

- Current maturity: strong mid-to-late stage fintech backend.
- Near-term potential: high, with targeted hardening in risk/recon/data-governance/security operations.
- Recommended path: convert existing robust primitives into strict, globally enforced platform contracts.

---

## 11. Priority Gap List (Concrete)

Critical (fix first):

1. Risk context completeness in payment confirm flow (IP/device and related telemetry).
2. Reconciliation timing classification logic should use per-record timestamps.
3. Projection lag semantics should switch from oldest to newest watermark.
4. Migration chain validation from empty DB (especially integrity table evolution).
5. Line-level statement storage for forensic reconciliation.

High:

1. Mandatory tenant context enforcement middleware.
2. Enforced API key scope authorization across all merchant APIs.
3. Stronger webhook and outbox misconfiguration alerting.
4. Per-domain SLOs and automated incident routing.

Medium:

1. Adaptive retry and routing intelligence for payments/dunning.
2. Entitlement model linked to subscription state machine.
3. Enhanced projection replay/backfill at larger scale.

---

## 12. Roadmap (Execution-Oriented)

### 0-30 Days

1. Fix risk context inputs in payment confirm endpoint.
2. Correct recon timing-window classification implementation.
3. Change projection lag metric to latest-update watermark.
4. Add migration CI job that boots empty DB and applies V1-V69 end-to-end.
5. Introduce statement line persistence schema and ingestion path.

### 31-90 Days

1. Enforce tenant context at transaction boundary by default.
2. Implement API key scope checks in Spring Security authorization layer.
3. Add recon auto-remediation proposals with operator approval flow.
4. Add payment routing effectiveness analytics and policy feedback loop.

### 91-180 Days

1. Add globalized ledger extensions (FX revaluation, period close, multi-book).
2. Add policy-as-code guardrails for fintech compliance and operational controls.
3. Add full data lineage and event schema compatibility governance.
4. Build high-scale projection replay and event backfill framework.

---

## 13. Final Assessment

This codebase is not a lightweight prototype. It already has serious fintech architecture patterns and many correctness safeguards in place.

Current readiness verdict:

- Ready for controlled production usage with disciplined operations and moderate scale.
- Not yet ready for very high-scale, globally regulated, multi-region fintech workloads without targeted hardening.

Most important strategic point:

- You already have most foundational primitives.
- The next phase is about enforcing them universally, closing context gaps, and strengthening operational automation.

If those priorities are executed, this platform can evolve into a highly reliable subscription and payments core suitable for organizations with Stripe/Juspay/Slice-level reliability expectations while supporting Atlassian-style internal operational rigor.

---

## 14. Notes on Analysis Method

This document is based on direct repository analysis of:

- service-layer business logic,
- entity/repository constraints,
- migration scripts V1-V69,
- scheduler and reliability patterns,
- risk/recon/reporting operational workflows.

No runtime benchmark or live traffic replay was executed during this pass, so performance and behavioral findings are code-informed rather than load-test-verified in this document.
