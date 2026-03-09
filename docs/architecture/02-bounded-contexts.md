# Bounded Contexts

Each module in the FirstClub platform owns a clear **bounded context**: a set of entities it is solely responsible for writing, reading, and enforcing invariants on. Cross-context reads happen through service interfaces. No module bypasses another module's repository layer.

---

## 1. Membership

| Field | Value |
|---|---|
| **Purpose** | Define the tier hierarchy and plan catalog for V1 (Silver/Gold/Platinum) |
| **Core entities** | `MembershipTier`, `MembershipPlan` |
| **Write responsibilities** | Create/update tiers and plans with pricing and benefit metadata |
| **Read responsibilities** | Serve plan catalog to subscription creation flow |
| **Outbound dependencies** | → Subscription (plan reference) |
| **Inbound dependencies** | None (catalog root) |
| **Source-of-truth tables** | `membership_tiers`, `membership_plans` |
| **Projection tables** | None |

---

## 2. Merchant

| Field | Value |
|---|---|
| **Purpose** | Multi-tenant root. Every domain entity is scoped to a `merchant_id`. |
| **Core entities** | `Merchant` |
| **Write responsibilities** | Create and update merchant profiles; manage API keys |
| **Read responsibilities** | Merchant lookup for all tenant-scoped queries |
| **Outbound dependencies** | None (all other contexts depend on merchant) |
| **Inbound dependencies** | All modules reference `merchant_id` |
| **Source-of-truth tables** | `merchants`, `merchant_api_keys` |
| **Projection tables** | None |

---

## 3. Customer

| Field | Value |
|---|---|
| **Purpose** | Consumer identity and profile management |
| **Core entities** | `Customer` |
| **Write responsibilities** | Create/update customer profiles; PII is encrypted at write time via `@Converter` |
| **Read responsibilities** | Customer lookup, PII decryption on read |
| **Outbound dependencies** | → Merchant (tenant scope) |
| **Inbound dependencies** | Subscription, Billing, Payments, Notifications |
| **Source-of-truth tables** | `customers` |
| **Projection tables** | None |
| **Notes** | `phone_number` and `address` fields are AES-256-GCM encrypted at rest |

---

## 4. Catalog

| Field | Value |
|---|---|
| **Purpose** | Flexible V2 product and pricing catalog (products + price objects) |
| **Core entities** | `Product`, `Price`, `ProductFeature` |
| **Write responsibilities** | Create/update products, prices, features |
| **Read responsibilities** | Serve available plans and prices to subscription engine |
| **Outbound dependencies** | → Merchant |
| **Inbound dependencies** | Subscription V2 |
| **Source-of-truth tables** | `products`, `prices`, `product_features` |
| **Projection tables** | None |

---

## 5. Subscription

| Field | Value |
|---|---|
| **Purpose** | Manage the full subscription lifecycle: create, upgrade, downgrade, cancel, renew |
| **Core entities** | `SubscriptionV2`, `SubscriptionStatusHistory` |
| **Write responsibilities** | State transitions (ACTIVE, PAST_DUE, SUSPENDED, CANCELLED); version increment on every write |
| **Read responsibilities** | Current subscription status, billing cycle dates, renewal schedule |
| **Outbound dependencies** | → Catalog (plan), → Billing (invoice trigger), → Dunning (retry schedule), → Ledger (via billing) |
| **Inbound dependencies** | Dunning, Revenue Recognition |
| **Source-of-truth tables** | `subscriptions_v2`, `subscription_status_history` |
| **Projection tables** | `proj:sub-status` (Redis cache, future) |
| **Concurrency** | `@Version` optimistic lock on `subscriptions_v2.version` |

---

## 6. Billing

| Field | Value |
|---|---|
| **Purpose** | Invoice creation, line-item calculation, discount/proration/credit application, invoice lifecycle |
| **Core entities** | `Invoice`, `InvoiceLine`, `InvoiceSequence`, `CreditNote`, `Discount`, `DiscountRedemption` |
| **Write responsibilities** | Generate invoices; apply discounts and credits; calculate totals atomically |
| **Read responsibilities** | Invoice lookup with line items; outstanding balance queries |
| **Outbound dependencies** | → Subscription, → Tax (GST calc), → Ledger (on payment) |
| **Inbound dependencies** | Payment (triggers invoice payment status), Dunning |
| **Source-of-truth tables** | `invoices`, `invoice_lines`, `invoice_sequences`, `credit_notes`, `discounts`, `discount_redemptions` |
| **Projection tables** | `proj:invoice-summary` (Redis cache, future) |
| **Invariants** | `grand_total == sum of lines`; terminal invoices (PAID/VOID/UNCOLLECTIBLE) immutable |

---

## 7. Tax

| Field | Value |
|---|---|
| **Purpose** | India GST calculation: CGST/SGST (intra-state) or IGST (inter-state) |
| **Core entities** | `TaxRate`, `TaxConfig` |
| **Write responsibilities** | Manage tax rate configurations per merchant and HSN/SAC code |
| **Read responsibilities** | Compute tax lines for invoice generation |
| **Outbound dependencies** | → Merchant |
| **Inbound dependencies** | Billing |
| **Source-of-truth tables** | `tax_rates`, `tax_configs` |
| **Projection tables** | None |

---

## 8. Payments

| Field | Value |
|---|---|
| **Purpose** | Payment intent orchestration, gateway coordination, attempt management |
| **Core entities** | `PaymentIntentV2`, `PaymentAttemptV2`, `PaymentMethod` |
| **Write responsibilities** | Create payment intents; track attempts (monotonic numbering); update status on gateway callback |
| **Read responsibilities** | Intent status, captured/refunded/disputed amounts |
| **Outbound dependencies** | → Routing (gateway selection), → Ledger (on capture), → Risk (pre-auth check), → Billing (invoice payment) |
| **Inbound dependencies** | Refund, Dispute, Reconciliation |
| **Source-of-truth tables** | `payment_intents_v2`, `payment_attempts_v2`, `payment_methods` |
| **Projection tables** | `proj:payment-summary` (Redis cache, future) |
| **Invariants** | One SUCCEEDED capture per intent; `refunded_amount <= captured_amount`; terminal intents accept no new attempts |

---

## 9. Routing

| Field | Value |
|---|---|
| **Purpose** | Select the optimal payment gateway for each transaction based on configured rules and gateway health |
| **Core entities** | `RoutingRule`, `GatewayConfig`, `GatewayHealthSnapshot` |
| **Write responsibilities** | Update gateway health snapshots; persist routing decisions |
| **Read responsibilities** | Evaluate routing rules; read gateway health |
| **Outbound dependencies** | → Merchant, → Payments |
| **Inbound dependencies** | Payments (calls routing before gateway dispatch) |
| **Source-of-truth tables** | `routing_rules`, `gateway_configs`, `gateway_health_snapshots` |
| **Projection tables** | `routing` cache (Redis, future) |

---

## 10. Refunds / Disputes

| Field | Value |
|---|---|
| **Purpose** | Full refund lifecycle (PENDING → APPROVED → PROCESSED) and dispute/chargeback lifecycle (OPEN → WON/LOST) |
| **Core entities** | `RefundV2`, `Dispute`, `DisputeEvidence` |
| **Write responsibilities** | Issue refunds; open and transition disputes; post accounting entries |
| **Read responsibilities** | Refund status; dispute timeline; evidence documents |
| **Outbound dependencies** | → Ledger (accounting entries), → Payments (update refunded/disputed amounts) |
| **Inbound dependencies** | Reconciliation |
| **Source-of-truth tables** | `refunds_v2`, `disputes`, `dispute_evidence` |
| **Invariants** | `refunded_amount <= captured_amount`; `dispute_reserve >= 0`; no double-refund |

---

## 11. Ledger

| Field | Value |
|---|---|
| **Purpose** | Immutable double-entry accounting journal for all financial events |
| **Core entities** | `LedgerAccount`, `LedgerEntry`, `LedgerLine` |
| **Write responsibilities** | Post balanced journal entries; enforce balance constraint (DR == CR) |
| **Read responsibilities** | Trial balance; account balances; period reports |
| **Outbound dependencies** | None (terminal accounting sink) |
| **Inbound dependencies** | Billing, Payments, Refunds, Disputes, Revenue Recognition, Settlement |
| **Source-of-truth tables** | `ledger_accounts`, `ledger_entries`, `ledger_lines` |
| **Projection tables** | `ledger_balance_snapshots` |
| **Invariants** | Every entry balanced; no edits or deletes ever; correction = reversal |

---

## 12. Revenue Recognition

| Field | Value |
|---|---|
| **Purpose** | ASC 606 / IFRS 15 compliant daily amortization of deferred subscription revenue |
| **Core entities** | `RevenueRecognitionSchedule` |
| **Write responsibilities** | Generate daily schedule on invoice finalization; post `DR SUBSCRIPTION_LIABILITY / CR REVENUE_SUBSCRIPTIONS` per schedule row |
| **Read responsibilities** | Pending schedules; recognized revenue by period |
| **Outbound dependencies** | → Ledger (posts entries) |
| **Inbound dependencies** | Billing (schedule generated on invoice creation) |
| **Source-of-truth tables** | `revenue_recognition_schedules` |
| **Invariants** | `schedule total == invoice.recognizable_amount`; no duplicate posting; posted row must reference ledger entry |

---

## 13. Dunning

| Field | Value |
|---|---|
| **Purpose** | Retry-based payment recovery engine for failed subscription renewals (PAST_DUE handling) |
| **Core entities** | `DunningAttempt`, `DunningPolicy`, `DunningPolicySnapshot` |
| **Write responsibilities** | Schedule retry attempts; cancel pending attempts on recovery; snapshot policy at attempt creation |
| **Read responsibilities** | Pending attempts per subscription; policy configuration |
| **Outbound dependencies** | → Payments (retry payment), → Subscription (status update on success/failure) |
| **Inbound dependencies** | Subscription (triggers dunning on PAST_DUE) |
| **Source-of-truth tables** | `dunning_attempts`, `dunning_policies`, `dunning_policy_snapshots` |
| **Invariants** | One effective due attempt at a time per subscription; successful recovery cancels all future pending attempts |

---

## 14. Outbox

| Field | Value |
|---|---|
| **Purpose** | Transactional outbox pattern for reliable domain event publication |
| **Core entities** | `DomainEventOutbox` |
| **Write responsibilities** | Write events in same DB transaction as the triggering operation |
| **Read responsibilities** | PENDING events for polling/delivery |
| **Outbound dependencies** | → Events module (delivers to domain event log) |
| **Inbound dependencies** | All modules write outbox events |
| **Source-of-truth tables** | `domain_events_outbox` |
| **Invariants** | Replay does not duplicate money effects; dedup fingerprints preserved; metadata always populated |

---

## 15. Events

| Field | Value |
|---|---|
| **Purpose** | Immutable domain event log — append-only audit trail of all business events |
| **Core entities** | `DomainEvent` |
| **Write responsibilities** | Receive events from outbox poller; persist to event log |
| **Read responsibilities** | Event replay; audit queries; timeline views |
| **Outbound dependencies** | → Notification (triggers webhook delivery) |
| **Inbound dependencies** | Outbox |
| **Source-of-truth tables** | `domain_events` |
| **Notes** | Replay endpoint supports `VALIDATE_ONLY` mode — checks accounting invariants without mutating state |

---

## 16. Notifications & Webhooks

| Field | Value |
|---|---|
| **Purpose** | Deliver domain events to merchant-registered webhook endpoints; manage delivery lifecycle |
| **Core entities** | `WebhookEndpoint`, `WebhookDelivery` |
| **Write responsibilities** | Create delivery attempts; update delivery status (PENDING → DELIVERED / FAILED); disable endpoints after threshold failures |
| **Read responsibilities** | Delivery status; endpoint configuration |
| **Outbound dependencies** | → Events (consumes events to trigger delivery) |
| **Inbound dependencies** | None |
| **Source-of-truth tables** | `webhook_endpoints`, `webhook_deliveries` |
| **Notes** | HMAC-SHA256 signature on each delivery; retry with exponential backoff |

---

## 17. Risk

| Field | Value |
|---|---|
| **Purpose** | Pre-payment risk controls: velocity limit checks and IP block list enforcement |
| **Core entities** | `RiskProfile`, `IpBlockList`, `VelocityRecord` |
| **Write responsibilities** | Record velocity events; manage IP block list |
| **Read responsibilities** | Check velocity limits; check IP block status before payment capture |
| **Outbound dependencies** | → Merchant |
| **Inbound dependencies** | Payments (calls risk before gateway dispatch) |
| **Source-of-truth tables** | `risk_profiles`, `ip_block_list`, `velocity_records` |

---

## 18. Reconciliation

| Field | Value |
|---|---|
| **Purpose** | 4-layer nightly financial integrity check across invoices, payments, ledger, and external settlement statements |
| **Core entities** | `ReconBatch`, `ReconMismatch`, `SettlementBatch`, `ExternalStatementLine` |
| **Write responsibilities** | Create recon batches; record mismatches; update mismatch lifecycle |
| **Read responsibilities** | Summary reports; mismatch detail; CSV export |
| **Outbound dependencies** | → Ledger, → Payments, → Billing |
| **Inbound dependencies** | None (runs as scheduled job + on-demand) |
| **Source-of-truth tables** | `recon_batches`, `recon_mismatches`, `settlement_batches`, `external_statement_lines` |
| **Invariants** | Rerun idempotent; one payment in at most one batch; mismatch lifecycle valid |

---

## 19. Reporting & Projections

| Field | Value |
|---|---|
| **Purpose** | Pre-materialized query results for dashboards and support tools |
| **Core entities** | `LedgerBalanceSnapshot`, `SubscriptionProjection` |
| **Write responsibilities** | Materialize ledger snapshots nightly; rebuild projections on demand |
| **Read responsibilities** | Fast dashboard queries; subscription status cache |
| **Outbound dependencies** | → Ledger, → Subscription |
| **Inbound dependencies** | None |
| **Source-of-truth tables** | `ledger_balance_snapshots` |
| **Projection tables** | `subscription_projections` (updated by event listeners) |

---

## 20. Platform / Ops

| Field | Value |
|---|---|
| **Purpose** | Cross-cutting concerns: idempotency enforcement, feature flags, distributed job locks, DLQ management, deep health |
| **Core entities** | `IdempotencyKeyEntity`, `FeatureFlag`, `JobLock` |
| **Write responsibilities** | Persist idempotency responses; acquire/release job locks; flip feature flags |
| **Read responsibilities** | Check idempotency duplicates; read feature flags; health status aggregation |
| **Outbound dependencies** | None (used by all other modules) |
| **Inbound dependencies** | All modules |
| **Source-of-truth tables** | `idempotency_keys`, `feature_flags`, `job_locks` |

---

## Context Interaction Summary

```
Merchant ← owns everything
Customer ← belongs to merchant
Catalog  ← products / prices owned by merchant
Subscription ← references plan from Catalog
Billing  ← triggered by Subscription lifecycle
  └── Tax  ← called during invoice line calculation
  └── Discount/Credits applied inline
Payment  ← triggered to satisfy Invoice
  └── Routing ← called to select gateway
  └── Risk    ← called before gateway dispatch
  └── Ledger  ← called on capture/failure
Refund / Dispute ← follow Payment
  └── Ledger ← accounting entries
Revenue Recognition ← follows Invoice (schedule generated)
  └── Ledger ← daily posting
Dunning ← follows Subscription PAST_DUE
  └── Payment ← retry
Outbox ← written by all modules in same TX
  └── Events ← receives and persists
     └── Notifications ← delivers to merchant endpoints
Reconciliation ← reads Billing, Payments, Ledger, Settlement
Reporting ← reads Ledger for snapshots
Platform ← cross-cuts all (idempotency, locks, flags)
```
