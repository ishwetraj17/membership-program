# Subscription Contracts

Phase 4 of the FirstClub Membership Platform. Subscription contracts separate
the commercial agreement (what a customer signed up for, at what price, with
what billing schedule) from catalogue discovery.

---

## 1. Design Goals

| Goal | Implementation |
|---|---|
| Price snapshot at creation | `priceVersionId` is resolved and locked in at `createSubscription` |
| One active sub per customer/product | `BLOCKING_STATUSES` guard in `createSubscription` |
| Trial support | `status = TRIALING` when `price.trialDays > 0`; `trialEndsAt` set |
| Cancel-at-period-end | Flag only — status stays unchanged; billing engine honours it later |
| Future-action scheduling | `subscription_schedules` table; execution in Phase 6+ |
| Concurrent safety | `@Version Long version` on `subscriptions_v2` (optimistic locking) |
| Tenant isolation | All queries scoped by `merchantId` |
| No legacy breakage | Kept `com.firstclub.membership` subscription domain intact |

---

## 2. Database Schema

### `subscriptions_v2`

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL PK` | |
| `merchant_id` | `BIGINT FK → merchant_accounts` | |
| `customer_id` | `BIGINT FK → customers` | |
| `product_id` | `BIGINT FK → products` | |
| `price_id` | `BIGINT FK → prices` | |
| `price_version_id` | `BIGINT FK → price_versions` | snapshot locked at creation |
| `status` | `VARCHAR(32) NOT NULL` | see §4 |
| `billing_anchor_at` | `TIMESTAMP` | first billing reference point |
| `current_period_start` | `TIMESTAMP` | |
| `current_period_end` | `TIMESTAMP` | |
| `next_billing_at` | `TIMESTAMP` | used by billing engine |
| `cancel_at_period_end` | `BOOLEAN DEFAULT FALSE` | flag only |
| `cancelled_at` | `TIMESTAMP` | set on immediate cancel |
| `pause_starts_at` | `TIMESTAMP` | set on pause |
| `pause_ends_at` | `TIMESTAMP` | set on resume |
| `trial_ends_at` | `TIMESTAMP` | set when TRIALING |
| `metadata_json` | `TEXT` | arbitrary key/value |
| `version` | `BIGINT DEFAULT 0` | optimistic locking |
| `created_at` | `TIMESTAMP` | |
| `updated_at` | `TIMESTAMP` | |

**Indexes**
- `idx_sub_v2_merchant_customer_status` on `(merchant_id, customer_id, status)`
- `idx_sub_v2_merchant_next_billing` on `(merchant_id, next_billing_at)`

### `subscription_schedules`

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL PK` | |
| `subscription_id` | `BIGINT FK → subscriptions_v2` | |
| `scheduled_action` | `VARCHAR(32) NOT NULL` | see §5 |
| `effective_at` | `TIMESTAMP NOT NULL` | must be in the future at creation time |
| `payload_json` | `TEXT` | action-specific parameters |
| `status` | `VARCHAR(32) NOT NULL DEFAULT 'SCHEDULED'` | |
| `created_at` | `TIMESTAMP` | |
| `updated_at` | `TIMESTAMP` | |

**Index**
- `idx_sub_schedule_sub_id_effective_at` on `(subscription_id, effective_at)`

---

## 3. Package Layout

```
com.firstclub.subscription
├── controller
│   ├── SubscriptionV2Controller.java
│   └── SubscriptionScheduleController.java
├── dto
│   ├── SubscriptionCreateRequestDTO.java
│   ├── SubscriptionResponseDTO.java
│   ├── SubscriptionScheduleCreateRequestDTO.java
│   └── SubscriptionScheduleResponseDTO.java
├── entity
│   ├── SubscriptionStatusV2.java          (enum)
│   ├── SubscriptionScheduledAction.java   (enum)
│   ├── SubscriptionScheduleStatus.java    (enum)
│   ├── SubscriptionV2.java
│   └── SubscriptionSchedule.java
├── exception
│   ├── SubscriptionException.java
│   └── SubscriptionStateMachine.java
├── mapper
│   ├── SubscriptionV2Mapper.java
│   └── SubscriptionScheduleMapper.java
├── repository
│   ├── SubscriptionV2Repository.java
│   └── SubscriptionScheduleRepository.java
└── service
    ├── SubscriptionV2Service.java
    ├── SubscriptionScheduleService.java
    └── impl/
        ├── SubscriptionV2ServiceImpl.java
        └── SubscriptionScheduleServiceImpl.java
```

---

## 4. Subscription Statuses

| Status | Terminal | Live | Meaning |
|---|---|---|---|
| `INCOMPLETE` | no | no | Created, awaiting first payment / activation |
| `TRIALING` | no | yes | In free trial; billing deferred |
| `ACTIVE` | no | yes | Billing normally |
| `PAST_DUE` | no | yes | Payment failed; retrying |
| `PAUSED` | no | no | Billing paused by merchant |
| `SUSPENDED` | no | no | System-suspended (e.g., repeated past-due) |
| `CANCELLED` | **yes** | no | Cancelled by merchant or customer |
| `EXPIRED` | **yes** | no | Reached configured end date |

### State Machine

```
INCOMPLETE  ──► TRIALING
INCOMPLETE  ──► ACTIVE
INCOMPLETE  ──► CANCELLED

TRIALING    ──► ACTIVE
TRIALING    ──► PAST_DUE
TRIALING    ──► CANCELLED

ACTIVE      ──► PAST_DUE
ACTIVE      ──► PAUSED
ACTIVE      ──► CANCELLED
ACTIVE      ──► EXPIRED

PAST_DUE    ──► ACTIVE
PAST_DUE    ──► SUSPENDED
PAST_DUE    ──► CANCELLED

PAUSED      ──► ACTIVE
PAUSED      ──► CANCELLED

SUSPENDED   ──► ACTIVE
SUSPENDED   ──► CANCELLED

CANCELLED   ──► (terminal — no transitions)
EXPIRED     ──► (terminal — no transitions)
```

Enforced by `SubscriptionStateMachine.assertTransition(from, to)` which throws
`SubscriptionException(400, INVALID_STATE_TRANSITION)` on illegal moves.

---

## 5. Scheduled Actions

| Action | Meaning |
|---|---|
| `CHANGE_PRICE` | Switch price at period boundary |
| `PAUSE` | Schedule a future pause |
| `RESUME` | Schedule a future resume |
| `CANCEL` | Schedule a future cancellation |
| `SWAP_PRICE` | Swap to a different price version |

Schedule statuses: `SCHEDULED → EXECUTED | CANCELLED | FAILED`

Business rules:
- `effectiveAt` must be strictly in the future at creation time.
- No two `SCHEDULED` entries may share the same `(subscription_id, effective_at)`.
- Terminal subscriptions cannot receive new schedules.
- Only `SCHEDULED` entries can be cancelled (DELETE endpoint).

---

## 6. API Reference

### Subscriptions

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v2/merchants/{merchantId}/subscriptions` | Create subscription |
| `GET` | `/api/v2/merchants/{merchantId}/subscriptions` | List (paginated, `?status=`) |
| `GET` | `/api/v2/merchants/{merchantId}/subscriptions/{id}` | Get by ID |
| `POST` | `/api/v2/merchants/{merchantId}/subscriptions/{id}/cancel` | Cancel (`?atPeriodEnd=`) |
| `POST` | `/api/v2/merchants/{merchantId}/subscriptions/{id}/pause` | Pause |
| `POST` | `/api/v2/merchants/{merchantId}/subscriptions/{id}/resume` | Resume |

All endpoints require `Authorization: Bearer <JWT>` with `ROLE_ADMIN`.

#### Create Subscription — Request Body

```json
{
  "customerId": 42,
  "productId": 7,
  "priceId": 3,
  "priceVersionId": 9,   // optional — resolved to current-effective if omitted
  "metadataJson": "{}"
}
```

#### Create Subscription — Status Logic

Resolved at creation:
- If `price.trialDays > 0` → status = `TRIALING`, `trialEndsAt = now + trialDays`
- Otherwise → status = `INCOMPLETE`

#### Cancel — Query Parameter

| `atPeriodEnd` | Effect |
|---|---|
| `false` (default) | Transition to `CANCELLED` immediately; set `cancelledAt` |
| `true` | Set `cancelAtPeriodEnd = true`; status unchanged (billing engine cancels at period end) |

### Schedules

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v2/merchants/{merchantId}/subscriptions/{subscriptionId}/schedules` | Create schedule |
| `GET` | `/api/v2/merchants/{merchantId}/subscriptions/{subscriptionId}/schedules` | List schedules |
| `DELETE` | `/api/v2/merchants/{merchantId}/subscriptions/{subscriptionId}/schedules/{scheduleId}` | Cancel schedule |

---

## 7. Error Codes

| Code | HTTP | Trigger |
|---|---|---|
| `SUBSCRIPTION_NOT_FOUND` | 404 | Subscription not found or not owned by merchant |
| `DUPLICATE_ACTIVE_SUBSCRIPTION` | 409 | Non-terminal subscription already exists for customer/product |
| `INVALID_STATE_TRANSITION` | 400 | Attempted illegal state machine transition |
| `SUBSCRIPTION_ALREADY_CANCELLED` | 400 | Trying to cancel a terminal subscription |
| `SUBSCRIPTION_ALREADY_PAUSED` | 400 | Trying to pause an already-paused subscription |
| `SUBSCRIPTION_NOT_PAUSED` | 400 | Trying to resume a non-paused subscription |
| `SUBSCRIPTION_TERMINAL` | 400 | Attempting to add a schedule to a terminal subscription |
| `NO_PRICE_VERSION_AVAILABLE` | 400 | No effective price version exists for the given price |
| `SCHEDULE_NOT_FOUND` | 404 | Schedule not found or not owned by subscription |
| `SCHEDULE_EFFECTIVE_AT_IN_PAST` | 400 | `effectiveAt` is not in the future |
| `DUPLICATE_SCHEDULE_CONFLICT` | 409 | Another SCHEDULED entry exists at the same `effectiveAt` |
| `SCHEDULE_NOT_CANCELLABLE` | 400 | Schedule is not in `SCHEDULED` state |

---

## 8. Key Implementation Notes

### Price Snapshot
`priceVersionId` is resolved at subscription creation and stored. Subsequent
price changes do not affect existing subscriptions until a `CHANGE_PRICE` or
`SWAP_PRICE` schedule is executed.

### Blocking Status Set
A new subscription is blocked if an existing subscription for the same
`(merchantId, customerId, productId)` is in any of:
`INCOMPLETE, TRIALING, ACTIVE, PAST_DUE, PAUSED, SUSPENDED`

### Optimistic Locking
`subscriptions_v2.version` is mapped via `@Version` on the JPA entity. Any
concurrent state change (e.g., simultaneous cancel + pause) will cause one
request to receive a `409 Conflict` from Spring's `ObjectOptimisticLockingFailureException`.

### Legacy Domain
The original `com.firstclub.membership` subscription domain (Subscription, SubscriptionController, etc.)
is untouched. Phase 4 introduces a parallel domain under `com.firstclub.subscription`.

---

## 9. Future Phases

| Phase | Work |
|---|---|
| 5 | Billing engine — charge execution, retry logic, dunning |
| 6 | Schedule execution — process `SCHEDULED` entries at `effectiveAt` |
| 7 | Webhooks / event emission on state transitions |
| 8 | Customer-facing portal API (read-only) |
