# Dunning & Subscription Renewal

Automated subscription renewal, payment-failure handling, and retry (dunning) logic.

---

## Overview

When a subscription period ends the **Renewal Scheduler** attempts to charge the
customer through the **Payment Gateway Port** (a thin abstraction over whichever
gateway is active). If the charge succeeds the subscription period advances
seamlessly. If it fails the subscription moves to `PAST_DUE` and the **Dunning
Scheduler** retries at progressively wider intervals.

```
         Subscription Period Ends
                    │
                    ▼
       ┌─── cancelAtPeriodEnd? ─── YES → CANCELLED
       │
       NO
       │
       ▼
  Create Invoice + PaymentIntent
       │
       ├── charge() SUCCESS ──────────────────────► ACTIVE (period advanced)
       │
       └── charge() FAILED ──► PAST_DUE + schedule dunning
                                        │
                             ┌──── Attempt 1 (+1h)
                             ├──── Attempt 2 (+6h)
                             ├──── Attempt 3 (+24h)
                             └──── Attempt 4 (+3d)
                                        │
                              Each attempt: charge()
                              ├── SUCCESS ──────────────────────► ACTIVE
                              └── FAILED
                                   └─── no more attempts? ──► SUSPENDED
```

---

## Retry / Dunning Schedule

| Attempt | Delay from failure | Description |
|---------|--------------------|-------------|
| 1       | + 1 hour           | Rapid retry |
| 2       | + 6 hours          | Same-day retry |
| 3       | + 24 hours         | Next-day retry |
| 4       | + 3 days           | Final attempt |

Defined as `RenewalService.DUNNING_OFFSETS` (a `List<Duration>`).

---

## Subscription State Transitions

```
ACTIVE
  │
  │  charge fails (renewal)
  ▼
PAST_DUE
  ├── charge succeeds (dunning) ──► ACTIVE
  └── all retries exhausted      ──► SUSPENDED
```

See [state-machines.md](state-machines.md) for the full subscription FSM and
enforcement details.

---

## `cancel_at_period_end` Flow

Setting `cancelAtPeriodEnd = true` (via `PATCH /api/v1/subscriptions/{id}/cancel-at-period-end`)
prevents the renewal scheduler from creating a new invoice. When the next
renewal is due the scheduler:

1. Detects `cancelAtPeriodEnd == true`.
2. Sets `status = CANCELLED`, records `cancelledAt`, and clears the flag.
3. Writes a `CANCELLED` event to `subscription_history`.
4. Stops — no invoice or payment intent is created.

The customer retains access until `endDate`; the subscription becomes
permanently CANCELLED after that date.

---

## Grace Period

When a charge fails a `graceUntil` timestamp is set (`now + 7 days`).
This is informational for the application layer; the scheduler does not
currently use `graceUntil` to gate retry attempts. It is cleared when
the subscription is reactivated or suspended.

---

## Schedulers

| Scheduler         | Rate      | Initial Delay | Bean                  |
|-------------------|-----------|---------------|-----------------------|
| `RenewalScheduler`  | 5 min   | 1 min         | `renewalScheduler`    |
| `DunningScheduler`  | 10 min  | 90 sec        | `dunningScheduler`    |

Both are `@Scheduled` tasks enabled by `@EnableScheduling` on
`MembershipApplication`.

---

## API Endpoint

### Set cancel-at-period-end flag

```
PATCH /api/v1/subscriptions/{id}/cancel-at-period-end?value=true
```

| Parameter | Type    | Description                             |
|-----------|---------|-----------------------------------------|
| `value`   | boolean | `true` to cancel at period end; `false` to undo |

Returns `200 OK` with the updated `SubscriptionDTO`.

---

## Payment Gateway Port

The `PaymentGatewayPort` interface decouples the renewal and dunning services
from any specific gateway implementation:

```java
public interface PaymentGatewayPort {
    enum ChargeOutcome { SUCCESS, FAILED }
    ChargeOutcome charge(Long paymentIntentId);
}
```

The default bean is `SimulatedPaymentGateway` which always returns `FAILED`
(useful for developer environments and local testing). For production replace it
with an implementation backed by Razorpay, Stripe, etc. Because
`SimulatedPaymentGateway` is a plain `@Component`, a `@MockBean` in tests
overrides it automatically.

---

## Database Schema

### `dunning_attempts`

| Column            | Type       | Description                              |
|-------------------|------------|------------------------------------------|
| `id`              | BIGSERIAL  | Primary key                              |
| `subscription_id` | BIGINT     | FK → `subscriptions.id`                  |
| `invoice_id`      | BIGINT     | FK → `invoices.id`                       |
| `attempt_number`  | INT        | 1-based sequence within a failure cycle  |
| `scheduled_at`    | TIMESTAMP  | When this attempt should be processed    |
| `status`          | VARCHAR    | `SCHEDULED` / `SUCCESS` / `FAILED`       |
| `last_error`      | TEXT       | Last error message (null on success)     |
| `created_at`      | TIMESTAMP  | Insert time                              |

Indexes: `idx_dunning_scheduled` on `(status, scheduled_at)`;
`idx_dunning_subscription` on `subscription_id`.

### New subscription columns (V9 migration)

| Column                | Type      | Default | Description                           |
|-----------------------|-----------|---------|---------------------------------------|
| `cancel_at_period_end`| BOOLEAN   | FALSE   | Cancel instead of renew at period end |
| `grace_until`         | TIMESTAMP | NULL    | Grace deadline after payment failure  |
| `paused_until`        | TIMESTAMP | NULL    | Reserved for pause feature            |
| `next_renewal_at`     | TIMESTAMP | NULL    | When the next renewal should trigger  |

---

## Design Notes

- **Idempotency**: each `processRenewal` call first verifies `status == ACTIVE`;
  each `processSingleAttempt` verifies `status == PAST_DUE` and
  `invoice.status == OPEN`. Stale scheduler runs cannot double-charge.
- **Transaction boundary**: each `processRenewal` / `processSingleAttempt`
  call runs in its own `@Transactional` context. A failure in one subscription
  does not roll back others.
- **Ledger**: the dunning path bypasses the webhook-based `WebhookProcessingService`;
  ledger entries are therefore NOT written for dunning charges. Ledger entries
  are written only for customer-initiated payments via the normal webhook flow.
- **State machine**: `PAST_DUE → ACTIVE` and `ACTIVE → PAST_DUE` are
  registered in `StateMachineValidator`. Dunning success re-uses the existing
  `InvoiceService.activateSubscription()` path which enforces this transition.
