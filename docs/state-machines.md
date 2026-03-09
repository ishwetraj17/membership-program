# State Machines

This document describes all statuses and allowed transitions for the three
state-machine-enforced entities in the FirstClub platform.  
The transitions are validated at runtime by `StateMachineValidator`; any illegal
move throws a `MembershipException` (HTTP 400, error code `INVALID_STATUS_TRANSITION`).

---

## 1. Subscription (`SubscriptionStatus`)

Subscriptions follow the lifecycle below. CANCELLED is the only terminal state.

| From \ To          | PENDING | ACTIVE | PAST_DUE | SUSPENDED | EXPIRED | CANCELLED |
|--------------------|:-------:|:------:|:--------:|:---------:|:-------:|:---------:|
| **PENDING**        |         | ✅     |          |           |         | ✅        |
| **ACTIVE**         |         |        | ✅       | ✅        | ✅      | ✅        |
| **PAST_DUE**       |         | ✅     |          | ✅        |         | ✅        |
| **SUSPENDED**      |         | ✅     |          |           |         | ✅        |
| **EXPIRED**        |         | ✅     |          |           |         |           |
| **CANCELLED** ⛔   |         |        |          |           |         |           |

### Status descriptions

| Status      | Meaning                                                     |
|-------------|-------------------------------------------------------------|
| `PENDING`   | Subscription created; awaiting payment confirmation.        |
| `ACTIVE`    | Subscription is live and the member has full benefits.      |
| `PAST_DUE`  | Renewal payment failed; subscription is in a grace period.  |
| `SUSPENDED` | Access temporarily revoked (e.g. admin action or fraud).    |
| `EXPIRED`   | End date passed without renewal; can be reactivated.        |
| `CANCELLED` | *(terminal)* Member explicitly cancelled; no further moves. |

---

## 2. Invoice (`InvoiceStatus`)

Invoices are created in DRAFT and move through a linear billing flow.  
PAID, VOID, and UNCOLLECTIBLE are terminal states.

| From \ To             | DRAFT | OPEN | PAID | VOID | UNCOLLECTIBLE |
|-----------------------|:-----:|:----:|:----:|:----:|:-------------:|
| **DRAFT**             |       | ✅   |      | ✅   |               |
| **OPEN**              |       |      | ✅   | ✅   | ✅            |
| **PAID** ⛔           |       |      |      |      |               |
| **VOID** ⛔           |       |      |      |      |               |
| **UNCOLLECTIBLE** ⛔  |       |      |      |      |               |

### Status descriptions

| Status          | Meaning                                                          |
|-----------------|------------------------------------------------------------------|
| `DRAFT`         | Being prepared; not yet sent to the customer.                    |
| `OPEN`          | Finalised and sent; payment is expected.                         |
| `PAID`          | *(terminal)* Paid in full.                                       |
| `VOID`          | *(terminal)* Cancelled before payment.                           |
| `UNCOLLECTIBLE` | *(terminal)* Deemed uncollectable (e.g. customer in arrears).    |

---

## 3. PaymentIntent (`PaymentIntentStatus`)

PaymentIntents mirror the Stripe payment flow. SUCCEEDED is the only fully
terminal state; FAILED allows a single retry path.

| From \ To                   | RPM | RC | PROC | RA | SUCC | FAIL |
|-----------------------------|:---:|:--:|:----:|:--:|:----:|:----:|
| **REQUIRES_PAYMENT_METHOD** |     | ✅ |      |    |      | ✅   |
| **REQUIRES_CONFIRMATION**   |     |    | ✅   | ✅ |      | ✅   |
| **PROCESSING**              |     |    |      | ✅ | ✅   | ✅   |
| **REQUIRES_ACTION**         |     |    | ✅   |    |      | ✅   |
| **SUCCEEDED** ⛔            |     |    |      |    |      |      |
| **FAILED**                  | ✅  |    |      |    |      |      |

> Column abbreviations: **RPM** = REQUIRES_PAYMENT_METHOD · **RC** = REQUIRES_CONFIRMATION · **PROC** = PROCESSING · **RA** = REQUIRES_ACTION · **SUCC** = SUCCEEDED · **FAIL** = FAILED

### Status descriptions

| Status                      | Meaning                                                            |
|-----------------------------|--------------------------------------------------------------------|
| `REQUIRES_PAYMENT_METHOD`   | No payment method attached yet.                                    |
| `REQUIRES_CONFIRMATION`     | Payment method attached; awaiting explicit confirmation call.      |
| `PROCESSING`                | Payment is being processed by the provider.                        |
| `REQUIRES_ACTION`           | 3-D Secure or other customer action needed.                        |
| `SUCCEEDED`                 | *(terminal)* Payment completed successfully.                       |
| `FAILED`                    | Payment failed; may retry via REQUIRES_PAYMENT_METHOD.             |

---

## Enforcement

All transitions are enforced by
`com.firstclub.platform.statemachine.StateMachineValidator`.

```java
// Throws MembershipException(INVALID_STATUS_TRANSITION, 400) on illegal move
stateMachineValidator.validate("SUBSCRIPTION", oldStatus, newStatus);
stateMachineValidator.validate("INVOICE",      oldStatus, newStatus);
stateMachineValidator.validate("PAYMENT_INTENT", oldStatus, newStatus);
```

Tests live in `StateMachineValidatorTest` and cover every allowed and several
disallowed transitions for all three entities.

---

## 4. Dunning & Renewal Lifecycle

See [dunning.md](dunning.md) for the full dunning design doc. The key
transitions added by Phase 9:

### `cancel_at_period_end`

When `cancelAtPeriodEnd = true` is set on a subscription the renewal scheduler
cancels it at the next period boundary instead of renewing:

```
ACTIVE ──(cancelAtPeriodEnd=true, period ends)──► CANCELLED
```

### Renewal failure → PAST_DUE

```
ACTIVE ──(renewal charge FAILED)──► PAST_DUE
```

Four `DunningAttempt` rows are created (`status = SCHEDULED`) at +1h, +6h,
+24h, and +3d from the failure time.

### Dunning success → ACTIVE

```
PAST_DUE ──(dunning charge SUCCESS)──► ACTIVE
```

Handled by `DunningService` which delegates to the existing
`InvoiceService.activateSubscription()` path (enforced by `StateMachineValidator`).
Remaining `SCHEDULED` attempts are cancelled (set to `FAILED`).

### Dunning exhausted → SUSPENDED

```
PAST_DUE ──(all dunning attempts FAILED)──► SUSPENDED
```

`DunningService.checkAndSuspendIfExhausted()` transitions the subscription to
`SUSPENDED` and clears `graceUntil`.

### `DunningAttempt` states

| State       | Terminal | Description                                    |
|-------------|----------|------------------------------------------------|
| `SCHEDULED` | No       | Waiting for scheduled time to be processed     |
| `SUCCESS`   | Yes      | Charge succeeded; subscription reactivated     |
| `FAILED`    | Yes      | Charge failed or attempt was skipped/cancelled |
