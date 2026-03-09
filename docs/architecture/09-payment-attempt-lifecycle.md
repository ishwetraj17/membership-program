# 09 — Payment Attempt Lifecycle & UNKNOWN Outcome Path

## Why UNKNOWN Exists

In traditional payment systems, a gateway call returns one of two outcomes: **success** or **failure**. In reality, networks are unreliable:

- A gateway request times out after the HTTP deadline but **the gateway already processed the charge**.
- A network partition drops the response after the gateway commits.
- A server crash closes the connection before the response bytes arrive.

Treating any of these as a *failure* and retrying would result in a **duplicate charge**. Treating them as a *success* without confirmation would cause **revenue loss if the gateway never actually processed them**.

Phase 8 introduces a third outcome: **`UNKNOWN`** — "we dispatched the request, we don't know what happened."

---

## Payment Attempt State Machine

```
              ┌──────────────────────────────────────────────────────────┐
              │                  PaymentAttemptStatus                    │
              └──────────────────────────────────────────────────────────┘

  INITIATED ──► STARTED ──► AUTHORIZED ──► CAPTURED      (legacy AUTO capture)
                  │                           │
                  │                           └──► SUCCEEDED ✓  (Phase 8 terminal success)
                  │
                  ├──► FAILED ✗              (gateway declined / hard error)
                  │
                  ├──► TIMEOUT              (legacy alias; same as UNKNOWN for recovery)
                  │
                  ├──► UNKNOWN              (gateway did not respond — NOT terminal)
                  │         │
                  │         └──► SUCCEEDED ✓   (recovery: gateway confirmed success)
                  │         └──► FAILED ✗      (recovery: gateway confirmed failure)
                  │         └──► RECONCILED ⚠  (recovery: still ambiguous — manual review)
                  │
                  ├──► REQUIRES_ACTION      (3-D Secure / additional auth needed)
                  │
                  └──► CANCELLED            (operator-cancelled before dispatch)
```

### Terminal States

| Status       | Terminal | Resolvable | Description                                      |
|--------------|----------|------------|--------------------------------------------------|
| CAPTURED     | ✓        | —          | Legacy success (AUTO capture flow)               |
| SUCCEEDED    | ✓        | —          | Phase 8 terminal success                         |
| FAILED       | ✓        | —          | Gateway declined or network error confirmed      |
| TIMEOUT      | ✓        | —          | Legacy timeout (pre-Phase-8)                     |
| RECONCILED   | ✓        | —          | UNKNOWN resolved via async polling               |
| CANCELLED    | ✓        | —          | Cancelled before dispatch                        |
| **UNKNOWN**  | **✗**    | **✓**      | Gateway unresponsive — async recovery pending    |

UNKNOWN is the only **non-terminal, resolvable** state. The recovery scheduler will resolve it.

---

## Gateway Idempotency Keys

Every gateway request carries a `gateway_idempotency_key` in the format:

```
firstclub:{intentId}:{attemptNumber}
```

**Why this format?**
- **Deterministic**: The key is computed from stable, known values. If the service restarts and retries the same `(intentId, attemptNumber)`, the gateway will recognise the key and return the original result — **not process the payment again**.
- **Intent-scoped**: An `intentId` ties the idempotency key to a single payment intent, so retrying `intentId=42, attemptNumber=2` can never accidentally affect `intentId=43`.
- **Attempt-numbered**: Each retry attempt gets its own key, preventing cross-attempt collisions.

The key is stored in `payment_attempts.gateway_idempotency_key` with a `UNIQUE` partial index (WHERE NOT NULL), enforcing uniqueness at the database level.

---

## Single-Success-Per-Intent Invariant

A payment intent may only have **exactly one SUCCEEDED attempt** at any point in time. This is enforced at two layers:

1. **Service layer**: `PaymentAttemptServiceImpl.markSucceeded()` calls
   `countByPaymentIntentIdAndStatus(intentId, SUCCEEDED)` before the transition. If the
   count is > 0, it throws `PaymentIntentException.alreadySucceeded(intentId)`.
2. **Reconciler layer**: `PaymentOutcomeReconciler.reconcile()` calls `markSucceeded()`
   which inherits the same guard — a late gateway callback for an already-succeeded
   intent will throw and be logged rather than creating a duplicate charge.

---

## Async Reconciliation Flow

```
   Confirm Flow                    Recovery Scheduler
   ──────────────                  ───────────────────
   confirmPaymentIntent()          GatewayTimeoutRecoveryScheduler
        │                               │
        │ [timeout]                     │ every 5 min (default)
        ▼                               │
   markUnknown(attempt)                 │ finds UNKNOWN attempts
   intent.reconciliationState = PENDING │   older than stale threshold
        │                               │ (default: 2 min ago)
        │                               ▼
        └─────────────────► PaymentOutcomeReconciler.reconcile(attempt)
                                        │
                                        │ calls GatewayStatusResolver.resolveStatus()
                                        │
                                ┌───────┴──────────┐
                                │                  │
                    gateway says SUCCESS     gateway says FAILED   still UNKNOWN
                                │                  │                    │
                         markSucceeded()     markFailed()        markReconciled()
                         intent = SUCCEEDED  intent recon state  intent = REQUIRES_MANUAL_REVIEW
```

### Transaction isolation

`PaymentOutcomeReconciler.reconcile()` runs in `REQUIRES_NEW` propagation. Each attempt is resolved in its own transaction so a failure on attempt #2 does not roll back the already-committed resolution of attempt #1.

---

## New Database Columns (V54 migration)

### `payment_attempts`
| Column                   | Type          | Purpose                                          |
|--------------------------|---------------|--------------------------------------------------|
| `gateway_idempotency_key`| VARCHAR(200)  | Submitted to gateway to prevent re-processing    |
| `gateway_transaction_id` | VARCHAR(128)  | Gateway's own transaction reference              |
| `request_payload_hash`   | VARCHAR(128)  | SHA-256 of outbound request for audit            |
| `response_payload_json`  | TEXT          | Raw gateway response for reconciliation audit    |
| `processor_node_id`      | VARCHAR(255)  | App node that dispatched this attempt            |
| `started_at`             | TIMESTAMP     | When the gateway call was initiated              |

### `payment_intents_v2`
| Column                      | Type        | Purpose                                        |
|-----------------------------|-------------|------------------------------------------------|
| `last_successful_attempt_id`| BIGINT FK   | Points to the single SUCCEEDED attempt         |
| `reconciliation_state`      | VARCHAR(32) | Tracks async reconciliation lifecycle          |

---

## Configuration Reference

```properties
# How often the recovery scheduler runs (ISO-8601 duration, default 5 minutes)
payments.gateway.recovery.interval=PT5M

# Minimum age of UNKNOWN attempt before it is eligible for recovery polling
payments.gateway.recovery.stale-threshold-minutes=2

# Gateway call timeout (milliseconds)
payments.gateway.timeout-ms=5000

# Fraction (0-100) of simulated calls to force into TIMEOUT state (0 = disabled)
payments.gateway.simulated-timeout-rate=0
```

---

## Security Considerations

- `gateway_idempotency_key` is indexed with a **partial unique constraint** (`WHERE NOT NULL`)
  to prevent duplicate charge vectors through key collision.
- `response_payload_json` may contain sensitive gateway data. Access to this column should
  be restricted at the database level and excluded from external-facing API responses
  (it is not surfaced in `PaymentAttemptResponseDTO`).
- The reconcile endpoint (`POST /{id}/reconcile-gateway-status`) requires `ROLE_ADMIN`
  (inherited from the controller-level `@PreAuthorize`).
