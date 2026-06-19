# Payment Architecture — Production Hardening

The payment layer is **PSP-agnostic** and ready for a real provider integration without a redesign.
No provider (Stripe/Razorpay/Cashfree/Juspay/…) is referenced anywhere in the code — only a clean
port plus production-grade decorators around it.

```
SubscriptionServiceImpl
        │  builds ChargeRequest/RefundRequest with a DETERMINISTIC idempotency key
        ▼
MeteredPaymentGateway        (@Primary)   ── success/failure/pending + latency
        ▼
ResilientPaymentGateway                   ── circuit breaker + retry (transient only)
        ▼
PaymentGateway (port)  ◄── @Qualifier("paymentProvider")
        ▼
NoOpPaymentGateway (demo)  ──►  replaced by a real PSP adapter (same qualifier)
                                 adapter uses PaymentClientFactory (timeouts)
                                 adapter confirms async charges via PaymentWebhookPort
```

The decorators are wired by Spring qualifiers, so dropping in a real adapter is a one-bean swap:
implement `PaymentGateway`, annotate `@Qualifier("paymentProvider")`, delete `NoOpPaymentGateway`.
Resilience, metrics, idempotency, and the webhook seam all keep working unchanged.

---

## Part 1 — Contract review & gaps closed

The original port was `charge(userId, amount, description)` / `refund(reference, amount)` returning
`PaymentResult(reference, success)`. Gaps and fixes:

| Gap | Fix |
|-----|-----|
| No idempotency key → a retry could double-charge | `ChargeRequest`/`RefundRequest` carry a required, deterministic `idempotencyKey` |
| No trace correlation across services/PSP | requests carry a `correlationId` (from the request MDC, or fresh for jobs) |
| Boolean outcome couldn't model async PSPs | `PaymentResult.Status { SUCCEEDED, FAILED, PENDING }`; `success()` kept for callers |
| No decline reason | `PaymentResult.failureReason` |
| No transient/permanent distinction | `PaymentTransientException` marks unknown-outcome transport failures (retryable); declines stay as `FAILED` results |
| No currency | `ChargeRequest.currency` (ISO-4217) |

The **saga and compensation flow are unchanged**: reserve a PENDING subscription (committed) →
charge **outside** any DB transaction → activate on success, or compensate (cancel + refund) on
failure. The refund-on-failure path now also carries a deterministic refund key.

---

## Part 2 — Idempotency

Every charge/refund carries an idempotency key derived from **business identity**, computed in
`SubscriptionServiceImpl`:

| Operation | Key |
|-----------|-----|
| Create | `charge:create:<pendingSubId>` |
| Manual renew | `charge:renew:<subId>:<periodEnd>` |
| Auto-renew (recurring) | `charge:autorenew:<subId>:<nextBillingDate>` |
| Upgrade | `charge:upgrade:<subId>:<newPlanId>` |
| Trial conversion | `charge:trial:<subId>:<trialEndDate>` |
| Any refund | `refund:<originalReference>` (or `refund:sub:<subId>`) |

Because the key is a pure function of the operation, **every retry of the same logical charge
re-sends the same key**, so the PSP deduplicates it. This is layered on top of the existing
API-level `Idempotency-Key` (the `IdempotencyRecord` table) that makes `createSubscription` itself
replay-safe — the two operate at different layers (client→service vs service→PSP).

---

## Part 3 — Timeouts

`PaymentClientProperties` (`payment.client.*`) defines connect / read / write timeouts with
production-safe defaults (2s / 5s / 5s), all overridable. `PaymentClientFactory` is the extension
point: a future adapter calls `newRestClientBuilder()` to get a `RestClient.Builder` pre-wired with
those timeouts (connect + read on the JDK client; write exposed for clients that support a distinct
one). It's a factory, not an autoconfigured client, so it never clobbers the app's default
`RestClient.Builder`.

---

## Parts 4 & 5 — Circuit breaker + retry

`ResilientPaymentGateway` wraps **charge, refund, and recurring billing** (recurring flows through
the same `charge` path) with Resilience4j, composed as `retry(circuitBreaker(call))`:

* **Circuit breaker** (`payment.resilience.circuit-breaker.*`): COUNT-based window, opens at a 50%
  failure rate, stays **OPEN** (fail-fast) for `wait-duration-in-open-state`, then
  **HALF_OPEN** probes decide **recovery** (CLOSED) or re-open. Auto transition OPEN→HALF_OPEN is
  enabled. Only `PaymentTransientException` (and slow calls) count toward opening.
* **Retry** (`payment.resilience.retry.*`): up to 3 attempts with exponential backoff, **retrying
  only `PaymentTransientException`**.

**Why this can never double-charge:**

1. A retry re-invokes the delegate with the **same `ChargeRequest`** → same idempotency key → PSP
   dedupes.
2. Only *unknown-outcome transport failures* are retried. A **decline returns a `FAILED` result
   without throwing**, so it is never retried and never trips the breaker.

Tuning is explicit (`PaymentResilienceConfig` builds the registries from typed properties), not
annotation magic — so the protected behaviour is unit-testable.

---

## Part 6 — Payment metrics

| Meter | Source | Covers |
|-------|--------|--------|
| `membership.payment{operation,result}` (Timer) | `MeteredPaymentGateway` | success / failure / **pending** count **and latency** (member-perceived, incl. retry/backoff) |
| `resilience4j_retry_calls{kind}` | resilience4j-micrometer | retries (successful/failed with/without retry) |
| `resilience4j_circuitbreaker_state` | resilience4j-micrometer | circuit-breaker state (closed/open/half-open) |
| `resilience4j_circuitbreaker_calls{kind}` | resilience4j-micrometer | not-permitted / failed / successful calls |

All are scraped at `/actuator/prometheus` (ADMIN-only; see [OBSERVABILITY.md](OBSERVABILITY.md)).

---

## Part 7 — Webhook readiness (seam only)

For PSPs that confirm asynchronously, the building blocks exist with **no PSP and no route**:

* `PaymentResult.Status.PENDING` — model an accepted-but-unconfirmed charge.
* `PaymentNotification` — a normalized, provider-agnostic inbound event (eventId, reference, status).
* `PaymentWebhookPort` — the reconciliation seam a future signature-verifying controller calls;
  implementations must be idempotent on `eventId` (webhooks are at-least-once).
* `LoggingPaymentWebhookHandler` — safe no-op default so the seam is concrete and testable today.

When a real PSP is integrated, the only new code is a thin signature-verifying controller and a
reconciliation implementation of the port — the contract is already fixed.

---

## Part 8 — Validation

`mvn clean test` → **BUILD SUCCESS, 155 tests, 0 failures, 4 skipped** (the 4 skips are the Postgres
Testcontainer suite — no Docker locally). Payment-specific coverage:

* **Saga + compensation** — existing `SubscriptionServiceTest` (create/renew/upgrade/downgrade/cancel,
  decline → compensate, apply-fail → refund) all pass against the evolved contract.
* **Idempotency** — `NoOpPaymentGatewayTest`: same key → same reference, different key → different
  reference. `ResilientPaymentGatewayTest`: retries reuse the identical idempotency key.
* **Retries** — retry a transient failure then succeed; a decline is **not** retried.
* **Circuit breaker** — opens after the failure-rate breach and fails fast (delegate untouched);
  HALF_OPEN probes recover it to CLOSED.

### Readiness assessment

**Ready** for a real PSP integration:

* PSP-agnostic port + qualifier-based decorator chain → adapter is a one-bean swap.
* Idempotency, retry, circuit breaker, timeouts, metrics, and the webhook seam are in place and
  tested.

**Remaining for go-live** (out of scope here, by instruction — no PSP integration):

* A concrete PSP adapter (HTTP calls via `PaymentClientFactory`, mapping declines→`FAILED` and
  transport errors→`PaymentTransientException`).
* The signature-verifying webhook controller + a reconciliation `PaymentWebhookPort` implementation.
* Secrets management for PSP API keys; a persisted PSP idempotency/charge ledger if stronger
  exactly-once auditing than the current event ledger is required.
