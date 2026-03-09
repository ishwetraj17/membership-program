# Risk Engine 2.0 — Phase 18

## Overview

Phase 18 replaces the two-condition risk check with a configurable rule engine, a persisted decision model, and a manual review queue for high-risk payments.

---

## Architecture

```
Payment Confirm Request
        │
        ▼
 PaymentIntentV2ServiceImpl
        │  injects
        ▼
 RiskDecisionService.evaluateForPaymentIntent(RiskContext)
        │
        ├─► RiskRuleService.evaluateRules(context)
        │         │
        │         ├─ load active rules for merchant (+ platform-wide)
        │         └─ iterate in priority order → dispatch to RuleEvaluator
        │               ├─ BlocklistIpEvaluator
        │               ├─ UserVelocityEvaluator
        │               ├─ IpVelocityEvaluator
        │               └─ DeviceReuseEvaluator
        │
        ├─► persist RiskDecision
        │
        └─► if REVIEW → ManualReviewService.createCase()
```

---

## Rule Model

A **`RiskRule`** defines a single fraud signal and the action to take when it fires.

| Field | Description |
|---|---|
| `id` | Auto-generated primary key |
| `merchantId` | `NULL` = platform-wide; non-null = merchant-specific |
| `ruleCode` | Human-readable identifier (e.g. `HIGH_VELOCITY_BLOCK`) |
| `ruleType` | Maps to a registered `RuleEvaluator` |
| `configJson` | JSON parameters consumed by the evaluator |
| `action` | `ALLOW`, `CHALLENGE`, `BLOCK`, `REVIEW` |
| `active` | Soft-disable without deleting |
| `priority` | Lower number = evaluated first |

### Supported Rule Types

| ruleType | Evaluator | config_json fields |
|---|---|---|
| `BLOCKLIST_IP` | `BlocklistIpEvaluator` | `{"score": 100}` |
| `USER_VELOCITY_LAST_HOUR` | `UserVelocityEvaluator` | `{"threshold": 5, "score": 30}` |
| `IP_VELOCITY_LAST_10_MIN` | `IpVelocityEvaluator` | `{"threshold": 3, "score": 50}` |
| `DEVICE_REUSE` | `DeviceReuseEvaluator` | `{"threshold": 3, "lookbackHours": 24, "score": 40}` |

New rule types can be added by implementing `RuleEvaluator` and registering it as a Spring `@Component`.

---

## Score Model

Score is **additive**: every matched rule contributes `config_json["score"]` to the total.

```
total_score = Σ config_json["score"]  for each matched rule
```

Score is informational — it is persisted in `risk_decisions` for audit and analysis but **does not drive the final action** directly.

---

## Decision Model — Strongest Action Wins

The final `RiskAction` is derived from the set of matched rules using a strict precedence hierarchy:

```
BLOCK  >  REVIEW  >  CHALLENGE  >  ALLOW
```

| Condition | Final Decision |
|---|---|
| No rules matched | `ALLOW` |
| Any matched rule has `BLOCK` | `BLOCK` |
| No BLOCK, but any has `REVIEW` | `REVIEW` |
| No BLOCK/REVIEW, but any has `CHALLENGE` | `CHALLENGE` |
| All matched rules have `ALLOW` | `ALLOW` |

---

## Decision Outcomes

### ALLOW
Payment proceeds normally through the gateway.

### CHALLENGE
Intent status is set to `REQUIRES_ACTION`. The client must complete an additional authentication step (e.g. 3DS challenge) before retrying confirmation.

### BLOCK
Intent status is set to `FAILED`. The payment is permanently stopped. A `RiskViolationException` is thrown (HTTP 403).

### REVIEW
Intent status is set to `REQUIRES_ACTION`. A `ManualReviewCase` is created in `OPEN` status. The payment **cannot proceed** until an admin resolves the case.

---

## RiskDecision Record

Every evaluation produces a persisted `RiskDecision`:

| Field | Description |
|---|---|
| `merchantId` | Merchant owning the intent |
| `paymentIntentId` | Intent being confirmed |
| `customerId` | Customer making the payment |
| `score` | Aggregate risk score from all matched rules |
| `decision` | Final `RiskAction` |
| `matchedRulesJson` | JSON array: `[{id, ruleCode, action}, ...]` |
| `createdAt` | Evaluation timestamp |

---

## Review Queue Lifecycle

```
OPEN
  ├── APPROVED   (reviewer approved the payment — terminal)
  ├── REJECTED   (reviewer blocked the payment — terminal)
  ├── ESCALATED  (escalated to tier-2 review)
  │     ├── APPROVED  (terminal)
  │     ├── REJECTED  (terminal)
  │     └── CLOSED    (terminal)
  └── CLOSED     (closed administratively — terminal)
```

### Admin Actions

| Endpoint | Action |
|---|---|
| `POST /review-cases/{id}/assign?userId=X` | Assign the case to a reviewer |
| `POST /review-cases/{id}/resolve` + body `{"resolution": "APPROVED"}` | Resolve the case |

Post-resolution, an `APPROVED` case signals that the payment may be retried. The admin uses the standard payment intent confirm endpoint to retry. A `REJECTED` case means the fraud is confirmed; no retry is permitted without explicit admin reset.

---

## API Endpoints

### Risk Rules — `/api/v2/admin/risk/rules`

| Method | Path | Description |
|---|---|---|
| `POST` | `/` | Create a risk rule |
| `GET` | `/` | List all rules (paginated) |
| `PUT` | `/{ruleId}` | Update an existing rule |

### Risk Decisions — `/api/v2/admin/risk/decisions`

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | List decisions; filter by `?merchantId=X` |

### Review Cases — `/api/v2/admin/risk/review-cases`

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | List cases; filter by `?status=OPEN` |
| `POST` | `/{caseId}/assign` | Assign to user `?userId=X` |
| `POST` | `/{caseId}/resolve` | Resolve with `{"resolution": "APPROVED\|REJECTED\|ESCALATED\|CLOSED"}` |

All endpoints require `ADMIN` role.

---

## Extending the Rule Engine

1. Create a Spring `@Component` that extends `AbstractRuleEvaluator` and implements `RuleEvaluator`.
2. Return a unique `ruleType()` string.
3. Implement `evaluate(RiskRule, RiskContext)`.
4. `RiskRuleService` auto-discovers it on startup via `List<RuleEvaluator>` injection.
5. Create rules via `POST /api/v2/admin/risk/rules` with the new `ruleType`.

---

## Design Decisions

- **Strongest action wins** over score-threshold approach: deterministic, auditable, easy to reason about.
- **Score is additive and informational**: useful for dashboards and future ML features without controlling current decisions.
- **REVIEW blocks payment progression**: intent → `REQUIRES_ACTION`; only admin resolution allows retry.
- **Platform-wide rules augment merchant rules**: both are evaluated; merchant-specific rules are not exclusive.
- **Null ip/deviceId context**: evaluators that require ip or deviceId return `false` when those fields are absent, rather than throwing.
