# Risk Review Flow

This document explains how the risk engine works, what cases it flags, and how operators review and act on risk flags.

---

## Risk Engine Components

| Component | Purpose |
|---|---|
| `VelocityChecker` | Counts how many payments a user has attempted in a recent time window |
| `IpBlockService` | Checks whether the request IP is on the block list |
| `RiskService` | Orchestrates velocity + IP checks; returns a `RiskDecision` |
| `RiskProfile` | Per-merchant configuration of velocity thresholds |

---

## When Risk Checks Run

Risk checks are called **synchronously** before a payment intent is dispatched to the gateway:

```
POST /api/v2/payments/intents
  │
  ├─► RiskService.evaluate(merchantId, customerId, requestIp, amount)
  │     ├─► VelocityChecker.check(customerId, merchantId)
  │     └─► IpBlockService.isBlocked(ip)
  │
  ├─► If BLOCKED → return 403 Forbidden
  └─► If ALLOWED → proceed to gateway dispatch
```

---

## Velocity Limits

Velocity limits are configurable per merchant via `RiskProfile`. Default thresholds:

| Window | Default Limit | Effect |
|---|---|---|
| 1 minute | 3 payment attempts per user | Block if exceeded |
| 1 hour | 10 payment attempts per user | Block if exceeded |
| 24 hours | 20 payment attempts per user | Block if exceeded |

A `VelocityRecord` row is inserted for each payment attempt. The checker counts records in the time window.

**Note:** With Redis deployed, velocity counters will use `{env}:firstclub:rl:payconfirm:{merchantId}:{customerId}` sliding window counters instead of DB queries.

---

## IP Block List

The `ip_block_list` table holds IP addresses manually added by operators.

### Add an IP to Block List

```
POST /api/v1/risk/block-ip
{
  "ip": "192.168.1.100",
  "reason": "Fraudulent payment attempts",
  "addedBy": "operator@firstclub.com"
}
```

### Remove an IP

```
DELETE /api/v1/risk/block-ip/{ip}
```

### View Block List

```
GET /api/v1/risk/block-list
```

---

## Risk Flags from Disputes

When a dispute is opened, a `RISK_DISPUTE_FLAGGED` outbox event is emitted. This event:
1. Is stored in `domain_events` for audit
2. Triggers a webhook to the merchant if they have enabled `dispute.opened` event subscriptions
3. Can feed into a future automated risk scoring pipeline

---

## Reviewing Risk Cases

### High Dispute Rate

```sql
-- Merchants with high dispute rate in last 30 days
SELECT pi.merchant_id,
       COUNT(d.id) AS total_disputes,
       COUNT(DISTINCT pi.customer_id) AS unique_customers_disputed,
       (COUNT(d.id)::FLOAT / COUNT(DISTINCT pi.id)) * 100 AS dispute_rate_pct
FROM payment_intents_v2 pi
JOIN disputes d ON d.payment_id = pi.id
WHERE pi.created_at > NOW() - INTERVAL '30 days'
GROUP BY pi.merchant_id
HAVING (COUNT(d.id)::FLOAT / COUNT(DISTINCT pi.id)) * 100 > 1.0
ORDER BY dispute_rate_pct DESC;
```

### High Velocity Users

```sql
-- Users who hit velocity limits in the last 24 hours
SELECT customer_id, merchant_id, COUNT(*) AS attempts
FROM velocity_records
WHERE recorded_at > NOW() - INTERVAL '24 hours'
GROUP BY customer_id, merchant_id
HAVING COUNT(*) > 10
ORDER BY attempts DESC;
```

### Failed Payments from Blocked IPs (to verify block list effectiveness)

```sql
SELECT pi.id, pi.created_at, pi.request_ip
FROM payment_intents_v2 pi
JOIN ip_block_list ibl ON ibl.ip = pi.request_ip
WHERE pi.created_at > NOW() - INTERVAL '7 days';
```

---

## Risk Review Process

### Daily Review Checklist

- [ ] Check dispute rate per merchant (target: < 1% of payments)
- [ ] Check new IPs blocked by rate limiting
- [ ] Review any `RISK_DISPUTE_FLAGGED` events in `domain_events` from yesterday
- [ ] Check `VelocityRecord` for users hitting daily limits repeatedly

### Escalation Triggers

| Trigger | Action |
|---|---|
| Merchant dispute rate > 2% | Contact merchant; review their fraud prevention |
| Same IP responsible for > 10 disputes | Add to IP block list |
| Same customer ID on > 5 failed payment intents in 24h | Flag for manual review; consider temporary suspension |
| Chargeback amount > ₹50,000 in a single day | Escalate to finance |

---

## Risk Configuration

Risk thresholds are stored in `risk_profiles` per merchant. To update:

```
PUT /api/v1/risk/profiles/{merchantId}
{
  "velocityLimit1Min": 5,
  "velocityLimit1Hour": 15,
  "velocityLimit24Hours": 30,
  "disputeAlertThreshold": 0.01
}
```

A merchant handling legitimate volume (e.g., B2B invoicing) may need their thresholds raised. Ensure a business justification is documented before increasing limits.

---

## Known Limitations

| Limitation | Workaround |
|---|---|
| IP velocity limits not yet implemented (only user velocity) | Add to Phase 2 |
| No ML-based scoring | Rules-based only; must manually tune thresholds |
| No real-time fraud network integration | Gateway integration required |
| No automatic suspension based on dispute rate | Operators must manually review and act |

---

## Phase 12: Using the Ops Timeline for Risk Investigation

> Added in Phase 12.  See `docs/architecture/04-read-paths.md` for the full timeline architecture.

Every domain event now produces one or two rows in `ops_timeline_events`, giving
risk reviewers a single, chronological view across the payment intent, invoice,
customer, and subscription associated with a suspicious transaction.

### Timeline queries for risk review

```bash
# Full history of a suspicious payment intent
curl "http://localhost:8080/api/v2/admin/timeline/payment/42?merchantId=1" \
  -H 'Authorization: Bearer <admin-token>'

# Trace the complete checkout flow by correlation ID
curl "http://localhost:8080/api/v2/admin/timeline/by-correlation/corr-abc-def?merchantId=1" \
  -H 'Authorization: Bearer <admin-token>'

# All events for a customer (captures dispute opens, subscription churn)
curl "http://localhost:8080/api/v2/admin/timeline/customer/101?merchantId=1" \
  -H 'Authorization: Bearer <admin-token>'
```

### Risk-relevant timeline event types

| Event type | Timeline title | What to look for |
|---|---|---|
| `RISK_DECISION_MADE` | `Risk decision: BLOCK/ALLOW/REVIEW` | Repeated BLOCK → pattern attack |
| `PAYMENT_ATTEMPT_FAILED` | `Payment attempt failed` | Failure category, gateway used |
| `DISPUTE_OPENED` | `Dispute opened` | Correlates dispute to exact payment |
| `REFUND_ISSUED` | `Refund issued` | High velocity with no dispute = friendly fraud |
| `SUBSCRIPTION_CANCELLED` | `Subscription cancelled` | Cancellation immediately after first billing |

### Investigation workflow

1. Look up the `paymentIntentId` from the risk alert.
2. Call `GET /api/v2/admin/timeline/payment/{paymentIntentId}` — review all attempts,
   failure reasons, and risk decisions.
3. Note the `correlationId` from the earliest attempt.
4. Call `GET /api/v2/admin/timeline/by-correlation/{correlationId}` — see the full
   checkout span: subscription creation → invoice → payment intent → outcome.
5. Look up the `customerId` and call `GET /api/v2/admin/timeline/customer/{id}` —
   check for prior disputes or cancellation patterns.
6. Escalate via the standard risk review process if anomalies are found.

---

## Phase 18: Score Decay, Posture & Explainability

> Added in Phase 18. These features make risk decisions *less static* and give operators
> more context to act confidently.

### Risk Score Decay

Raw risk scores decay over time using a **half-life formula**:

```
decayedScore = baseScore × 0.5^(ageHours / halfLifeHours)
```

Default half-life: **72 hours**.

| Age | Remaining score |
|---|---|
| 0 h | 100% |
| 72 h | ~50% |
| 144 h | ~25% |
| 216 h | ~12.5% |

Scores stored in `risk_events.decayed_score` are refreshed via `RiskScoreDecayService.decayAll()`.

### SLA Tracking for Manual Review Cases

Every manual review case now has a `sla_due_at` timestamp set to **24 hours** after creation.
Cases that breach their SLA without resolution are automatically moved to **ESCALATED** by
`ManualReviewEscalationService.escalateOverdueCases()`.

Trigger via the API:

```
POST /api/v2/risk/manual-reviews/escalate-overdue
Authorization: Bearer <admin-token>
```

Returns: `{ "escalatedCount": N }`

### Manual Review Quick Actions

| Endpoint | Description |
|---|---|
| `GET /api/v2/risk/manual-reviews` | List cases (filter by status) |
| `POST /api/v2/risk/manual-reviews/{id}/approve` | Quick-approve with optional note |
| `POST /api/v2/risk/manual-reviews/{id}/reject` | Quick-reject with optional note |
| `POST /api/v2/risk/manual-reviews/{id}/escalate` | Manually escalate with reason |
| `POST /api/v2/risk/manual-reviews/escalate-overdue` | Auto-escalate all SLA-breached cases |

### Merchant Risk Posture

Get a real-time snapshot of how risky a merchant's traffic has been recently:

```
GET /api/v2/risk/posture/{merchantId}
Authorization: Bearer <admin-token>
```

Sample response:
```json
{
  "merchantId": 1,
  "recentDecisionCount": 47,
  "blockCount": 8,
  "reviewCount": 12,
  "challengeCount": 4,
  "allowCount": 23,
  "avgScore": 42,
  "dominantAction": "ALLOW"
}
```

### Decision Explainability

Fetch a plain-English explanation of why a payment intent received its risk decision:

```
GET /api/v2/risk/decision-explanations/{paymentIntentId}
Authorization: Bearer <admin-token>
```

Sample response:
```json
{
  "paymentIntentId": 100,
  "decision": "BLOCK",
  "score": 75,
  "decayedScore": 53,
  "triggeredRuleIds": [3, 7],
  "matchedRules": "[{\"id\":3,...}, {\"id\":7,...}]",
  "explanation": "Payment intent 100 received decision BLOCK with a risk score of 75. 2 rule(s) fired (IDs: [3, 7]). The decision was recorded 38 minute(s) ago. After time-decay (72-hour half-life) the score is now 53."
}
```

### Audit Fields Added to Manual Review Cases

| Column | Populated when |
|---|---|
| `sla_due_at` | Case created (= createdAt + 24 h) |
| `escalated_at` | Case status transitions to ESCALATED |
| `decision_reason` | Case is resolved (APPROVED / REJECTED / CLOSED) or manually escalated |
| `closed_by` | Set by operator on resolve (future: populated from JWT subject) |
| `closed_at` | Case reaches a terminal status (APPROVED / REJECTED / CLOSED) |
