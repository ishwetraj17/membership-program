# Write Paths

This document describes every major **write path** in the system: the exact sequence of steps, DB tables written, events emitted, invariants enforced, and concurrency guards applied.

---

## 1. Subscription Creation

**Incoming API:** `POST /api/v2/subscriptions`

### Steps

| Step | Action | Details |
|---|---|---|
| 1 | Validate request | Customer exists, merchant exists, plan exists, billing cycle valid |
| 2 | Check idempotency | `IdempotencyFilter`: Redis NX lock + response cache (Layer 2+3), fall-through to `idempotency_keys` table (Layer 1) |
| 3 | Check risk | (if risk module enabled) velocity check via `RiskService` |
| 4 | Create subscription | Insert `subscriptions_v2` row with `status=ACTIVE`, `version=0`, `start_date`, `end_date` |
| 5 | Generate invoice | Billing module creates `Invoice` + `InvoiceLine`(s) for the first billing period |
| 6 | Apply discount | If promo code provided: validate `Discount`, apply `InvoiceLine(DISCOUNT)`, insert `DiscountRedemption` |
| 7 | Apply tax | Tax module calculates CGST/SGST or IGST; inserts `InvoiceLine`(CGST/SGST/IGST) |
| 8 | Recalculate totals | `InvoiceTotalServiceImpl` recomputes `subtotal`, `discount_total`, `tax_total`, `grand_total` |
| 9 | Generate revenue schedule | `RevenueRecognitionScheduleServiceImpl` creates one `RevenueRecognitionSchedule` row per day of service period |
| 10 | Write outbox event | `DomainEventOutbox` row inserted: event_type=`SUBSCRIPTION_CREATED` |
| 11 | Write idempotency response | `idempotency_keys` row updated with serialized response body |
| 12 | Commit transaction | All above in single `@Transactional` block |

### Schema Touched

`subscriptions_v2`, `subscription_status_history`, `invoices`, `invoice_lines`, `invoice_sequences` (atomic increment), `discount_redemptions` (optional), `tax_rates` (read), `revenue_recognition_schedules`, `domain_events_outbox`, `idempotency_keys`

### Redis

- Read: `{env}:firstclub:idem:resp:{merchantId}:{idempotencyKey}` — fast-path cache (Layer 2), checked before DB
- Read/Write: `{env}:firstclub:idem:lock:{merchantId}:{idempotencyKey}` — NX in-flight lock (Layer 3), 30 s TTL
- Write: `{env}:firstclub:idem:resp:{merchantId}:{idempotencyKey}` — cache seeded after DB response stored
- Read: `{env}:firstclub:merchant:settings:{merchantId}` — feature flags
- Write: `{env}:firstclub:proj:sub-status:{merchantId}:{subscriptionId}` — invalidate on create

### Events Emitted

`SUBSCRIPTION_CREATED` → triggers webhook delivery

### Invariants Enforced

- Invoice `grand_total == sum of line totals` (checked by `InvoiceTotalServiceImpl`)
- No billing period overlap on same customer + plan

### Concurrency Guard

- Idempotency: Redis NX lock prevents concurrent duplicate execution (30 s TTL safety valve); DB primary key unique constraint on composite key `"{merchantId}:{rawKey}"` is the final guard when Redis is unavailable
- `invoice_sequences`: `SELECT ... FOR UPDATE` on the sequence row to atomically increment invoice number

### Retry Behavior

- Client may retry with same idempotency key → returns cached response
- No partial state possible (single transaction)

---

## 2. Invoice Finalization

**Incoming API:** Internal — called by subscription creation and renewal jobs

### Steps

| Step | Action |
|---|---|
| 1 | Validate invoice is in `DRAFT` or `PENDING` state |
| 2 | Verify all line items are present |
| 3 | Recalculate totals via `InvoiceTotalServiceImpl` |
| 4 | Set `status = PENDING`, assign `due_date` |
| 5 | Post ledger entry: `DR RECEIVABLE / CR SUBSCRIPTION_LIABILITY` |
| 6 | Write outbox event: `INVOICE_CREATED` |
| 7 | Commit |

### Invariants Enforced

- `grand_total` matches recomputed value before finalization
- Terminal invoices (`PAID`, `VOID`, `UNCOLLECTIBLE`) cannot be re-finalized

---

## 3. Payment Intent Creation

**Incoming API:** `POST /api/v2/payments/intents`

### Steps

| Step | Action |
|---|---|
| 1 | Validate invoice exists and is in `PENDING` state |
| 2 | Check idempotency key |
| 3 | Call risk service: `VelocityChecker` + `IpBlockService` |
| 4 | Call routing: `PaymentRoutingServiceImpl` selects gateway — reads `RoutingRuleCache` (TTL 300 s) and `GatewayHealthCache` (TTL 60 s); DB fallback on miss |
| 5 | Create `PaymentIntentV2` row: `status=CREATED`, `gateway=selected`, `attempt_number=0` |
| 6 | Create initial `PaymentAttemptV2`: `attempt_number=1`, `status=INITIATED`; write `routing_snapshot_json` with the full `RoutingDecisionSnapshot` |
| 7 | Dispatch to selected gateway (simulated); receive `gateway_txn_id` |
| 8 | Write outbox event: `PAYMENT_INTENT_CREATED` |
| 9 | Commit |

### Schema Touched

`payment_intents_v2`, `payment_attempts_v2`, `domain_events_outbox`

### Redis

- Read: `{env}:firstclub:rl:payconfirm:{merchantId}:{customerId}` — rate limit
- Read: `{env}:firstclub:gw:health:{GATEWAY_NAME}` — gateway health for routing (TTL 60 s; DB fallback)
- Read: `{env}:firstclub:routing:{scope}:{methodType}:{currency}:{retryNumber}` — cached routing rules (TTL 300 s; DB fallback, populated on miss)
- Write: `payment_attempts.routing_snapshot_json` — `RoutingDecisionSnapshot` JSON persisted on the attempt row for audit

### Concurrency Guard

- Idempotency key
- Unique constraint: one active intent per invoice (`payment_intents_v2.invoice_id` with status filter)

### Cache Invalidation on Route Rule Mutations

When an operator calls `POST /admin/gateway-routes`, `PUT /admin/gateway-routes/{id}`, or `DELETE /admin/gateway-routes/{id}`:
1. DB row is created/updated/deactivated inside the transaction.
2. After commit: `RoutingRuleCache.evict(scope, methodType, currency, retryNumber)` removes the exact cache entry for the mutated rule.
3. The next routing decision for that discriminator combination triggers a DB load and repopulates the cache.

---

## 4. Payment Confirmation (Gateway Callback / Capture)

**Incoming API:** `POST /api/v2/payments/confirm` or gateway webhook callback

### Steps

| Step | Action |
|---|---|
| 1 | Parse callback payload; verify HMAC signature |
| 2 | Check dedup: `dedup:biz:PAYMENT_CAPTURE:{fingerprint}` (Redis future) or DB unique fingerprint |
| 3 | Check idempotency: if same gateway_txn_id already processed → return 200 without side effects |
| 4 | Load `PaymentIntentV2`; verify `status != terminal` |
| 5 | Update `PaymentAttemptV2.status = SUCCEEDED` |
| 6 | Update `PaymentIntentV2.status = SUCCEEDED`, set `captured_amount` |
| 7 | Update `Invoice.status = PAID`, set `paid_at` |
| 8 | Post ledger entry: `DR CASH / CR RECEIVABLE` |
| 9 | Write outbox event: `PAYMENT_SUCCEEDED` |
| 10 | Commit |

### Schema Touched

`payment_attempts_v2`, `payment_intents_v2`, `invoices`, `ledger_entries`, `ledger_lines`, `domain_events_outbox`

### Redis (Future)

- Read/Write: `{env}:firstclub:dedup:biz:PAYMENT_CAPTURE:{fingerprint}` — prevent double-capture
- Write: `{env}:firstclub:proj:payment-summary:{merchantId}:{intentId}` — invalidate

### Invariants Enforced

- One SUCCEEDED outcome per intent (DB unique constraint on `(intent_id, status=SUCCEEDED)`)
- `attempt_number` is monotonically increasing (enforced in service layer)
- Terminal intent rejects new attempts

### Concurrency Guard

- DB unique constraint on `business_fingerprint` in `payment_attempts_v2`
- `SELECT ... FOR UPDATE` on `PaymentIntentV2` row inside confirmation transaction

### Retry Behavior

- Duplicate callback with same `gateway_txn_id` → idempotent; returns 200, no new ledger entry
- Unsafe to retry if DB partial failure after ledger write (use idempotency key to detect)

---

## 5. Refund Creation

**Incoming API:** `POST /api/v2/refunds`

### Steps

| Step | Action |
|---|---|
| 1 | Validate: payment exists, `status=SUCCEEDED` |
| 2 | Validate: `requested_amount <= (captured_amount - refunded_amount)` |
| 3 | Check: no concurrent refund in progress (DB lock on payment row or unique pending refund) |
| 4 | Create `RefundV2` row: `status=PENDING` |
| 5 | Dispatch to gateway (simulated) |
| 6 | On APPROVED: update `RefundV2.status=COMPLETED` |
| 7 | Update `payment.refunded_amount += refund.amount` |
| 8 | Recalculate `payment.net_amount` |
| 9 | Post ledger entry: `DR REFUND_EXPENSE / CR CASH` |
| 10 | Write outbox event: `REFUND_ISSUED` |
| 11 | Commit |

### Schema Touched

`refunds_v2`, `payment_intents_v2`, `ledger_entries`, `ledger_lines`, `domain_events_outbox`

### Redis (Future)

- Write: `{env}:firstclub:lock:refund:{paymentId}` — short-lived processing lock (supplemental)

### Invariants Enforced

- `refunded_amount <= captured_amount` (hard check before insert)
- `dispute_reserve >= 0` (no overdraw)

### Concurrency Guard

- DB unique constraint prevents two refunds for same payment bringing `refunded_amount > captured_amount` via `CHECK` constraint or service-layer validation with `SELECT FOR UPDATE`

---

## 6. Dispute Opening

**Incoming API:** `POST /api/v2/disputes` or gateway webhook

### Steps

| Step | Action |
|---|---|
| 1 | Validate: payment exists and is SUCCEEDED |
| 2 | Validate: no existing OPEN dispute for same payment |
| 3 | Create `Dispute` row: `status=OPEN`, `due_by` set based on gateway type |
| 4 | Post ledger entry: `DR DISPUTE_RESERVE / CR CASH` |
| 5 | Update `payment.disputed_amount` |
| 6 | Recalculate `payment.net_amount` |
| 7 | Write outbox event: `DISPUTE_OPENED` |
| 8 | Write outbox event: `RISK_DISPUTE_FLAGGED` (if risk module active) |
| 9 | Commit |

### Schema Touched

`disputes`, `payment_intents_v2`, `ledger_entries`, `ledger_lines`, `domain_events_outbox`

### Redis (Future)

- Write: `{env}:firstclub:lock:dispute:{paymentId}` — short-lived processing lock

### Invariants Enforced

- `dispute_reserve >= 0`
- One open dispute per payment

### Dispute Resolution

- **Won (merchant wins):** `DR CASH / CR DISPUTE_RESERVE`
- **Lost (chargeback):** `DR CHARGEBACK_EXPENSE / CR DISPUTE_RESERVE`

---

## 7. Revenue Recognition Posting

**Trigger:** Nightly scheduler (`@Scheduled(cron="0 0 3 * * *")`) via `RevenueRecognitionPostingServiceImpl`

### Steps

| Step | Action |
|---|---|
| 1 | Acquire `JobLock("revenue_recognition_daily")` — INSERT then UPDATE-if-expired |
| 2 | Query all `RevenueRecognitionSchedule` WHERE `recognition_date <= today` AND `status=PENDING` |
| 3 | For each schedule row: call `self.postSingleRecognition(scheduleId)` in `REQUIRES_NEW` transaction |
| 4 | In `REQUIRES_NEW`: reload schedule row; if already `POSTED` → skip (idempotency guard) |
| 5 | Post ledger entry: `DR SUBSCRIPTION_LIABILITY / CR REVENUE_SUBSCRIPTIONS` |
| 6 | Set `schedule.ledger_entry_id = entry.id` |
| 7 | Set `schedule.status = POSTED` |
| 8 | Commit `REQUIRES_NEW` |
| 9 | Release job lock |

### Why `REQUIRES_NEW`?

Each schedule row commits independently. A failure on row N does not roll back rows 1..N-1. Failed rows remain `PENDING` and will be picked up on the next scheduler run.

### Invariants Enforced

- Schedule total == invoice's recognizable amount
- No duplicate posting: idempotency guard at step 4
- Posted row must reference ledger entry (set at step 6)

### Concurrency Guard

- `JobLock` prevents duplicate scheduler fire
- `REQUIRES_NEW` + status check prevents double-posting on crash-recovery

---

## 8. Reconciliation Run

**Trigger:** Nightly scheduler (`@Scheduled(cron="0 10 2 * * *")`) or `POST /api/v1/admin/recon/settle`

### Steps (Advanced 4-Layer)

| Layer | Comparison | Mismatch Type |
|---|---|---|
| 1 | Invoices ↔ Payments | `INVOICE_NO_PAYMENT`, `PAYMENT_NO_INVOICE`, `AMOUNT_MISMATCH`, `DUPLICATE_GATEWAY_TXN` |
| 2 | Payments ↔ Ledger | Payment captured amount vs PAYMENT_CAPTURED ledger lines |
| 3 | Ledger ↔ Settlement Batches | Settlement ledger lines vs batch gross amounts |
| 4 | Settlement Batches ↔ External Statement Lines | Batch vs bank statement |

### Steps

| Step | Action |
|---|---|
| 1 | Acquire `JobLock("reconciliation_daily")` |
| 2 | Create `ReconBatch` with `status=RUNNING` |
| 3 | For each layer: compute expected vs actual; insert `ReconMismatch` rows for discrepancies |
| 4 | Update `ReconBatch.status = COMPLETED` (or `FAILED` if exception) |
| 5 | Release job lock |

### Idempotency

Rerun for the same date is idempotent: existing `OPEN` mismatches are preserved; new mismatches are inserted; already-resolved mismatches are not touched.

### Invariants Enforced

- Tolerance: ±0.01 (half-paise rounding acceptable)
- One payment in at most one batch

---

## 9. Outbound Webhook Delivery

**Trigger:** Async — `WebhookDeliveryServiceImpl` polls `PENDING` deliveries

### Steps

| Step | Action |
|---|---|
| 1 | Domain event persisted (outbox → events module) |
| 2 | `WebhookDeliveryServiceImpl` creates `WebhookDelivery` row per matching endpoint |
| 3 | Delivery worker picks up `PENDING` delivery |
| 4 | Check `{env}:firstclub:webhook:endpoint:{endpointId}:disabled` — skip if disabled |
| 5 | Build payload; sign with `HMAC-SHA256` using endpoint's signing secret |
| 6 | POST to merchant's endpoint; record response code and body |
| 7 | If `2xx` → `status=DELIVERED` |
| 8 | If failure → increment `attempt_count`; if `attempt_count >= threshold` → mark endpoint `disabled` |
| 9 | Exponential backoff between retries |

### Concurrency Guard

- `{env}:firstclub:webhook:lock:delivery:{deliveryId}` — Redis lock prevents two workers processing same delivery
- DB unique constraint on `(delivery_id, attempt_number)` in delivery attempts table

### Retry Safety

- Safe to retry failed deliveries (idempotency is the merchant's responsibility on their end)
- HMAC signature allows merchant to deduplicate: same payload + same signature = same event

---

## Write Path Summary

| Write Path | Transactional? | Async Part | Primary Concurrency Guard |
|---|---|---|---|
| Subscription create | Yes (all in one TX) | Outbox poll | Idempotency key |
| Invoice finalization | Yes | Outbox poll | Terminal state check |
| Payment intent create | Yes | Outbox poll | Idempotency key + unique constraint |
| Payment confirmation | Yes | Outbox poll | Business fingerprint unique constraint |
| Refund create | Yes | Outbox poll | Refund ceiling check + SELECT FOR UPDATE |
| Dispute open | Yes | Outbox poll | One open dispute per payment |
| Revenue recognition | Per-row REQUIRES_NEW | — | JobLock + status idempotency + ceiling check (Phase 14) |
| Revenue schedule generation | Yes (atomic saveAll) | — | existsByInvoiceId + existsByGenerationFingerprint (Phase 14) |
| Reconciliation | Batch (non-financial, idempotent) | — | JobLock |
| Webhook delivery | Async post-event | Always async | Redis delivery lock |

---

## Phase 14 — Revenue Recognition Write Path Changes

Phase 14 hardened two write paths in the revenue engine.

### Schedule Generation (`generateScheduleForInvoice`)

```
1. existsByInvoiceId(invoiceId)?  → return existing (primary idempotency)
2. Load and validate invoice (PAID, subscription, period bounds)
3. Compute SHA-256 fingerprint = SHA-256(invoiceId:subId:grandTotal:periodStart:periodEnd)
4. existsByGenerationFingerprint(fingerprint)?  → return existing (secondary TOCTOU guard)
5. Build daily rows with fingerprint + catchUpRun=false
6. saveAll(rows)
```

Force-regeneration (catch-up) path (`regenerateScheduleForInvoice`):
```
1. Delete all PENDING rows for invoiceId (POSTED rows are immutable)
2. Load and validate invoice
3. Compute fingerprint
4. Build daily rows with fingerprint + catchUpRun=true
5. saveAll(rows)
```

### Single Recognition Posting (`postSingleRecognitionInRun`)

```
1. SELECT FOR UPDATE on schedule row (TOCTOU guard)
2. status == POSTED?  → return (idempotency)
3. Ceiling check: sum(POSTED for invoice) + this.amount ≤ sum(ALL for invoice)
4. Post double-entry: DR SUBSCRIPTION_LIABILITY / CR REVENUE_SUBSCRIPTIONS
5. Set status=POSTED, ledgerEntryId, postingRunId
6. save(schedule)
```

The batch runner generates a monotonic `postingRunId = System.currentTimeMillis()` at the start of each `postDueRecognitionsForDate()` invocation and passes it to every `postSingleRecognitionInRun()` call in that batch, enabling per-run audit queries.

### Waterfall Projection Update

The waterfall projection is a **derived read model** — it is never a source of truth and can be recomputed at any time.

```
1. sumPostedAmountByMerchantAndDate(merchantId, date) from schedules
2. Find or create revenue_waterfall_projection row for (merchantId, date)
3. Set recognized_amount, compute deferred_closing
4. Save (UPSERT semantics)
```

