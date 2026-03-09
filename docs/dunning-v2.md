# Dunning 2.0 — Policy-Driven Retry Lifecycle

## Overview

Dunning 2.0 replaces hard-coded retry offsets with **merchant-configurable policies** that control:

- Retry schedule (minutes from failed charge)
- Maximum number of retry attempts
- Grace window (days after which no new attempts are created)
- Backup payment method fallback
- Terminal outcome when all retries are exhausted (`SUSPENDED` or `CANCELLED`)

V1 and V2 dunning coexist in the same `dunning_attempts` table. V2 rows have a non-null `dunning_policy_id`; legacy V1 rows keep `dunning_policy_id = null` and are processed exclusively by the original `DunningService`.

---

## Database Schema

### `dunning_policies`

| Column | Type | Description |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `merchant_id` | BIGINT NOT NULL → `merchant_accounts` | Tenant FK |
| `policy_code` | VARCHAR(64) | Unique slug per merchant |
| `retry_offsets_json` | TEXT | JSON array of minutes, e.g. `[60, 360, 1440, 4320]` |
| `max_attempts` | INT | Upper cap on attempts created |
| `grace_days` | INT | Days window; attempts after this are not created |
| `fallback_to_backup_payment_method` | BOOLEAN | Enable backup PM on primary failure |
| `status_after_exhaustion` | VARCHAR(32) | `SUSPENDED` or `CANCELLED` |
| `created_at` | TIMESTAMP | |

Unique constraint: `(merchant_id, policy_code)`.

### `subscription_payment_preferences`

| Column | Type | Description |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `subscription_id` | BIGINT NOT NULL UNIQUE → `subscriptions_v2` | One record per subscription |
| `primary_payment_method_id` | BIGINT NOT NULL → `payment_methods` | Primary instrument |
| `backup_payment_method_id` | BIGINT NULL → `payment_methods` | Optional backup |
| `retry_order_json` | TEXT NULL | Reserved for future use |
| `created_at`, `updated_at` | TIMESTAMP | |

### `dunning_attempts` — new columns (V27 migration)

| Column | Type | Description |
|---|---|---|
| `dunning_policy_id` | BIGINT NULL → `dunning_policies` | Non-null for V2 attempts |
| `payment_method_id` | BIGINT NULL | Audit: which PM was attempted |
| `used_backup_method` | BOOLEAN NOT NULL DEFAULT FALSE | Whether backup PM was used |

---

## Policy Resolution Order

`DunningPolicyService.resolvePolicy(merchantId)` returns the effective policy:

1. Merchant has a policy with `policy_code = 'DEFAULT'` → use it.
2. No DEFAULT but other policies exist → use the first one found.
3. No policies at all → **auto-create** DEFAULT with:
   - `retryOffsetsJson = [60, 360, 1440, 4320]` (1 h / 6 h / 24 h / 3 d)
   - `maxAttempts = 4`, `graceDays = 7`, `fallbackToBackupPaymentMethod = false`
   - `statusAfterExhaustion = SUSPENDED`

---

## Retry Schedule

`DunningServiceV2.scheduleAttemptsFromPolicy(subscriptionId, invoiceId, merchantId)`:

1. Resolve policy for the merchant.
2. Parse `retryOffsetsJson` into `List<Integer>` of minutes.
3. For each offset up to `min(offsets.size(), maxAttempts)`:
   - Compute `scheduledAt = now + offset_minutes`.
   - If `scheduledAt > now + graceDays` → stop (grace window exceeded).
   - Save `DunningAttempt` with `dunningPolicyId` set and `status = SCHEDULED`.

---

## Attempt Processing

`DunningSchedulerV2` runs every 5 minutes and triggers `processDueV2Attempts()`.

For each due attempt (`dunning_policy_id IS NOT NULL AND status = SCHEDULED AND scheduled_at ≤ now`):

```
1. Load policy
2. Load SubscriptionV2 → must be PAST_DUE (skip otherwise)
3. Load Invoice → must be OPEN (skip otherwise)
4. Resolve PM:
     - attempt.usedBackupMethod=true  → preference.backupPaymentMethodId
     - otherwise                       → preference.primaryPaymentMethodId (may be null)
5. Create PaymentIntent + charge
6. SUCCESS:
     - invoiceService.onPaymentSucceeded(invoiceId)
     - sub.status = ACTIVE
     - Cancel remaining SCHEDULED v2 attempts
     - Record DUNNING_V2_SUCCEEDED event
7. FAILURE:
     - Mark attempt FAILED
     - If policy.fallback AND backup PM exists AND !usedBackupMethod:
         → Schedule immediate backup attempt (usedBackupMethod=true)
         → Record DUNNING_V2_BACKUP_QUEUED event
     - Else:
         → checkAndApplyTerminalStatus()
```

### Terminal Status Logic

`checkAndApplyTerminalStatus(subscriptionId, policyId)`:

1. Count remaining `SCHEDULED` v2 attempts for this subscription.
2. If any remain → return (not yet exhausted).
3. If subscription is no longer `PAST_DUE` → return (already resolved).
4. Read `policy.statusAfterExhaustion`:
   - `CANCELLED` → `sub.status = CANCELLED`, set `cancelledAt = now()`
   - `SUSPENDED` → `sub.status = SUSPENDED`
5. Record `DUNNING_V2_EXHAUSTED` event.

---

## Backup Payment Method Flow

```
Primary fails
     │
     ▼
policy.fallbackToBackupPaymentMethod = true?
     │ yes            │ no
     ▼                ▼
preference.backupPmId exists?    → checkAndApplyTerminalStatus
     │ yes            │ no
     ▼                ▼
attempt.usedBackupMethod = false?    → checkAndApplyTerminalStatus
     │ yes
     ▼
Create immediate DunningAttempt {
    usedBackupMethod = true,
    paymentMethodId  = backupPmId,
    scheduledAt      = now()
}
```

---

## REST API

### Dunning Policies

```
POST   /api/v2/merchants/{merchantId}/dunning-policies
GET    /api/v2/merchants/{merchantId}/dunning-policies
GET    /api/v2/merchants/{merchantId}/dunning-policies/{policyCode}
```

#### POST — Create Policy

```json
{
  "policyCode": "AGGRESSIVE",
  "retryOffsetsJson": "[60, 360, 1440]",
  "maxAttempts": 3,
  "graceDays": 5,
  "fallbackToBackupPaymentMethod": true,
  "statusAfterExhaustion": "CANCELLED"
}
```

Response `201 Created`:
```json
{
  "id": 1,
  "merchantId": 42,
  "policyCode": "AGGRESSIVE",
  "retryOffsetsJson": "[60, 360, 1440]",
  "maxAttempts": 3,
  "graceDays": 5,
  "fallbackToBackupPaymentMethod": true,
  "statusAfterExhaustion": "CANCELLED",
  "createdAt": "2024-01-15T10:00:00"
}
```

**Validation errors:**

| Error code | HTTP | Cause |
|---|---|---|
| `DUPLICATE_POLICY_CODE` | 409 | `policyCode` already exists for this merchant |
| `INVALID_RETRY_OFFSETS_JSON` | 422 | Not a valid JSON array / empty / negative offsets |
| `INVALID_MAX_ATTEMPTS` | 422 | `maxAttempts ≤ 0` |
| `INVALID_TERMINAL_STATUS` | 422 | `statusAfterExhaustion` not `SUSPENDED` or `CANCELLED` |
| `DUNNING_POLICY_NOT_FOUND` | 404 | Code not found for merchant |

### Payment Preferences

```
PUT  /api/v2/merchants/{merchantId}/subscriptions/{subscriptionId}/payment-preferences
GET  /api/v2/merchants/{merchantId}/subscriptions/{subscriptionId}/payment-preferences
```

#### PUT — Set Preferences

```json
{
  "primaryPaymentMethodId": 100,
  "backupPaymentMethodId": 200
}
```

- Creates or **replaces** the existing preference record (upsert).
- Both PMs must belong to the subscription's customer within the merchant scope.
- Primary and backup must differ.
- Both PMs must have `status = ACTIVE`.

Response `200 OK`:
```json
{
  "id": 5,
  "subscriptionId": 10,
  "primaryPaymentMethodId": 100,
  "backupPaymentMethodId": 200,
  "retryOrderJson": null,
  "createdAt": "2024-01-15T10:00:00",
  "updatedAt": "2024-01-15T10:00:00"
}
```

**Validation errors:**

| Error code | HTTP | Cause |
|---|---|---|
| `SUBSCRIPTION_NOT_FOUND` | 404 | Subscription not found for merchant |
| `PAYMENT_METHOD_NOT_FOUND` | 422 | PM not found / doesn't belong to customer |
| `PAYMENT_METHOD_NOT_ACTIVE` | 422 | PM status is not `ACTIVE` |
| `DUPLICATE_PAYMENT_METHODS` | 422 | Primary and backup IDs are identical |
| `PREFERENCE_NOT_FOUND` | 404 | No preferences set (GET only) |

### Dunning Attempts

```
GET /api/v2/merchants/{merchantId}/subscriptions/{subscriptionId}/dunning-attempts
```

Returns all dunning attempts for the subscription (V1 and V2 combined), validating merchant ownership.

---

## Domain Events

| Event type | Triggered when |
|---|---|
| `DUNNING_V2_SCHEDULED` | `scheduleAttemptsFromPolicy` creates attempts |
| `DUNNING_V2_SUCCEEDED` | Retry attempt succeeds, sub → ACTIVE |
| `DUNNING_V2_BACKUP_QUEUED` | Primary fails, backup PM attempt queued |
| `DUNNING_V2_EXHAUSTED` | All attempts exhausted, terminal status applied |

---

## Scheduler

`DunningSchedulerV2` runs every **5 minutes** (initial delay 105 s, offset from V1 scheduler at 90 s) and calls `processDueV2Attempts()`.

---

## V1 / V2 Coexistence

| Aspect | V1 | V2 |
|---|---|---|
| `dunning_policy_id` | `null` | Non-null |
| Retry schedule | Hard-coded `[60, 360, 1440, 4320]` min | Merchant policy |
| Terminal outcome | Always `SUSPENDED` | `SUSPENDED` or `CANCELLED` per policy |
| Backup PM | Not supported | Supported via `SubscriptionPaymentPreference` |
| Processor | `DunningService` | `DunningServiceV2` |
| Subscription entity | `Subscription` (V1) | `SubscriptionV2` |

V1 and V2 attempts live in the same `dunning_attempts` table. Each engine queries only its own rows (V2 uses `dunning_policy_id IS NOT NULL` predicate; V1 effectively sees all rows via the old `findByStatusAndScheduledAtLessThanEqual` query, but V2 rows reference `SubscriptionV2` IDs and will fail merchant checks in V1 unless IDs collide).

> **Production recommendation:** Run `DunningService` only for subscriptions managed by the old `Subscription` entity, and `DunningServiceV2` only for `SubscriptionV2` subscriptions. A discriminator column (e.g. `subscription_version`) would cleanly separate them.

---

## Testing

| Class | Type | Tests |
|---|---|---|
| `DunningPolicyServiceTest` | Unit | 12 tests — create, validate, list, get, resolve policy |
| `SubscriptionPaymentPreferenceServiceTest` | Unit | 8 tests — set preferences, get preferences, validation |
| `DunningServiceV2Test` | Unit | 9 tests — schedule, process (success/failure/backup/exhaustion) |
| `DunningPolicyControllerTest` | Integration | 7 tests — full HTTP round-trip with Testcontainers Postgres |
| `SubscriptionPaymentPreferenceControllerTest` | Integration | 8 tests — full HTTP round-trip with Testcontainers Postgres |
