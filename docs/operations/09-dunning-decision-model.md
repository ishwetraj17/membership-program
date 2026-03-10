# Dunning Decision Model

> Phase 16 — Failure-code intelligence and backup payment strategy

## Overview

The Phase 16 dunning engine replaces blind retries with a decision model that classifies
every gateway failure code and chooses the appropriate next action.  No retry is issued
unless retrying is likely to succeed.

---

## Failure Classification

Each payment failure carries a raw gateway code (e.g. `stolen_card`, `insufficient_funds`).
`FailureCodeClassifier` normalises the code to a `FailureCategory`:

| Category | Example codes | Retry behaviour |
|---|---|---|
| `INSUFFICIENT_FUNDS` | `insufficient_funds` | Retry — funds may become available |
| `CARD_DECLINED_GENERIC` | `card_declined`, `generic_decline` | Retry — transient issue |
| `GATEWAY_TIMEOUT` | `gateway_timeout`, `processing_error` | Retry — network transient |
| `CARD_EXPIRED` | `expired_card`, `invalid_expiry_year` | Switch to backup PM |
| `CARD_NOT_SUPPORTED` | `card_not_supported`, `currency_not_supported` | Switch to backup PM |
| `ISSUER_NOT_AVAILABLE` | `issuer_not_available`, `call_issuer` | Switch to backup PM |
| `CARD_STOLEN` | `stolen_card` | **Stop immediately** |
| `CARD_LOST` | `lost_card` | **Stop immediately** |
| `FRAUDULENT` | `fraudulent` | **Stop immediately** |
| `DO_NOT_HONOR` | `do_not_honor`, `no_action_taken` | **Stop immediately** |
| `INVALID_ACCOUNT` | `invalid_account`, `account_blacklisted` | **Stop immediately** |
| `UNKNOWN` | unrecognised codes | Retry (treated as generic decline) |

Lookup is case-insensitive; hyphens are normalised to underscores before lookup.

---

## Strategy Decision Rules

`DunningStrategyService.decide(attempt, category, policy)` returns a `DunningDecision`:

```
1. NON_RETRYABLE (CARD_STOLEN, CARD_LOST, FRAUDULENT, DO_NOT_HONOR, INVALID_ACCOUNT)
       → STOP  (immediately cancel queue, apply terminal status)

2. NEEDS_BACKUP (CARD_EXPIRED, CARD_NOT_SUPPORTED, ISSUER_NOT_AVAILABLE)
       AND policy.fallbackToBackupPaymentMethod = true
       AND attempt.usedBackupMethod = false
       AND backup PM configured for subscription
       → RETRY_WITH_BACKUP  (queue immediate attempt with backup PM)

       ELSE → STOP  (instrument is broken, no recovery path)

3. Remaining SCHEDULED attempts = 0
       → EXHAUSTED  (apply terminal status from policy)

4. (default)
       → RETRY  (let the existing scheduled queue proceed)
```

---

## Actions Per Decision

| Decision | Action |
|---|---|
| `RETRY` | No special action; the next scheduled attempt in the queue will fire at its scheduled time. |
| `RETRY_WITH_BACKUP` | A new `DunningAttempt` is created immediately (`scheduledAt = now`) with `usedBackupMethod = true` and `paymentMethodId = backupPaymentMethodId`. |
| `STOP` | All remaining SCHEDULED attempts are cancelled.  Terminal subscription status from the policy (`SUSPENDED` or `CANCELLED`) is applied.  `stopped_early = true` on the triggering attempt. |
| `EXHAUSTED` | Terminal subscription status is applied (same as policy `statusAfterExhaustion`). |

---

## Per-Attempt Audit Fields

Every attempt processed by the v2 engine (Phase 16+) has the following columns populated after a failure:

| Column | Type | Description |
|---|---|---|
| `failure_code` | `VARCHAR(80)` | Raw code from the payment gateway |
| `failure_category` | `VARCHAR(40)` | Classified category name (see table above) |
| `decision_taken` | `VARCHAR(30)` | `DunningDecision` enum value |
| `decision_reason` | `TEXT` | Human-readable explanation |
| `stopped_early` | `BOOLEAN` | `true` when a non-retryable code halted the queue |
| `used_backup_method` | `BOOLEAN` | `true` when a backup PM was used (pre-Phase-16 field) |

---

## New API Endpoints (Phase 16)

### Get Policy by Numeric ID

```
GET /api/v2/merchants/{merchantId}/dunning-policies/id/{policyId}
```

Returns the dunning policy identified by its database ID.  Useful when the caller
has a `dunning_policy_id` from a `DunningAttempt` record.

### Force-Retry a Failed Attempt

```
POST /api/v2/merchants/{merchantId}/dunning-attempts/{attemptId}/force-retry
```

Creates an immediate `SCHEDULED` dunning attempt based on the specified `FAILED`
attempt.  Use this for ops intervention after a subscriber updates their payment
method or when automated retries have been exhausted.

**Constraints:**
- The source attempt must be in `FAILED` state.
- The subscription must belong to the given merchant.
- Returns `409 CONFLICT` if the attempt is not in `FAILED` state.

### List Dunning Attempts for Subscription (pre-existing, Phase 10)

```
GET /api/v2/merchants/{merchantId}/subscriptions/{subscriptionId}/dunning-attempts
```

Returns all v1 and v2 dunning attempts for the given subscription, ordered by creation time.

---

## Migration

**V62** (`V62__dunning_failure_intelligence.sql`) adds five columns to `dunning_attempts`:

```sql
ALTER TABLE dunning_attempts
    ADD COLUMN IF NOT EXISTS failure_code     VARCHAR(80),
    ADD COLUMN IF NOT EXISTS failure_category VARCHAR(40),
    ADD COLUMN IF NOT EXISTS decision_taken   VARCHAR(30),
    ADD COLUMN IF NOT EXISTS decision_reason  TEXT,
    ADD COLUMN IF NOT EXISTS stopped_early    BOOLEAN NOT NULL DEFAULT FALSE;
```

Two partial indexes are added for operational queries:
- `idx_dunning_stopped_early` — find all early-stopped attempts
- `idx_dunning_failure_category` — failure category breakdown analytics

---

## Component Map

```
com.firstclub.dunning.classification
├── FailureCategory               (enum — 12 categories)
└── FailureCodeClassifier         (@Component — normalise + lookup)

com.firstclub.dunning.strategy
├── DunningStrategyService        (interface)
├── DunningStrategyServiceImpl    (@Service — 4-rule decision logic)
└── BackupPaymentMethodSelector   (@Component — backup PM lookup)

com.firstclub.dunning
├── DunningDecision               (enum — RETRY, RETRY_WITH_BACKUP, STOP, EXHAUSTED)
└── DunningDecisionAuditService   (@Service — stamps decision fields on attempt)

com.firstclub.dunning.port
└── PaymentGatewayPort            (interface — ChargeResult added; chargeWithCode() added)

com.firstclub.dunning.controller
└── DunningAttemptController      (new — POST /force-retry)
```
