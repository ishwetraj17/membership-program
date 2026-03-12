# 11 — Observability, SLOs, and Deep Health

> Phase 21 — "Prove the system is healthy instead of chanting 'it seems okay.'"

---

## Overview

Phase 21 adds production-grade observability to the FirstClub membership platform:

| Layer | What Was Added |
|-------|---------------|
| **Metrics** | Counters, timers, and gauges via Micrometer → `/actuator/prometheus` |
| **Health indicators** | Four new Spring Boot `HealthIndicator` beans → `/actuator/health` |
| **Deep health API** | `GET /ops/health/deep` — composite health with scheduler + projection + SLO |
| **SLO status API** | `GET /ops/slo/status` — 6 defined SLOs evaluated against live metrics |

---

## 1. Metrics Inventory

### 1.1 Counters (`*_total` suffix in Prometheus)

These are monotonically-increasing event counts. They reset to `0` on pod restart.
Increment them from service code at the point the event occurs.

| Metric Name | When to Increment | Class |
|-------------|-------------------|-------|
| `payment.success.total` | Payment attempt reaches `SUCCEEDED` or `CAPTURED` | `FinancialMetrics.getPaymentSuccess()` |
| `payment.failure.total` | Payment attempt reaches `FAILED` | `FinancialMetrics.getPaymentFailure()` |
| `payment.unknown.total` | Payment attempt transitions to `UNKNOWN` | `FinancialMetrics.getPaymentUnknown()` |
| `refund.completed.total` | `RefundV2` transitions to `COMPLETED` | `FinancialMetrics.getRefundCompleted()` |
| `refund.failed.total` | `RefundV2` transitions to `FAILED` | `FinancialMetrics.getRefundFailed()` |
| `dunning.success.total` | Dunning sequence recovers subscription | `FinancialMetrics.getDunningSuccess()` |
| `dunning.exhausted.total` | Dunning sequence hits max retries | `FinancialMetrics.getDunningExhausted()` |
| `dispute.opened.total` | New dispute opened against a payment | `FinancialMetrics.getDisputeOpened()` |
| `ledger.invariant.violation.total` | Integrity checker detects a double-entry violation | `FinancialMetrics.getLedgerInvariantViolation()` |
| `outbox.dlq.total` | Outbox event moved to DLQ after max retries | `FinancialMetrics.getOutboxDlq()` |

**Usage in service code:**
```java
@Autowired FinancialMetrics metrics;

// On a successful capture:
metrics.getPaymentSuccess().increment();

// On a failed capture:
metrics.getPaymentFailure().increment();
```

### 1.2 Timers (latency distributions)

| Metric Name | What It Measures | Target |
|-------------|-----------------|--------|
| `payment.capture.latency` | End-to-end capture including gateway round-trip | P95 < 3 000 ms |
| `webhook.delivery.latency` | End-to-end delivery to merchant endpoint | P95 < 5 000 ms |
| `recon.run.duration` | Full reconciliation batch wall-clock time | < 60 s |
| `outbox.handler.duration` | Single outbox event dispatch time | < 500 ms |

**Usage in service code:**
```java
// Option 1: wrap with record(Runnable)
metrics.getPaymentCaptureLatency().record(() -> gateway.capture(request));

// Option 2: use Sample for async flows
Timer.Sample sample = Timer.start(registry);
// ... async work ...
sample.stop(metrics.getPaymentCaptureLatency());
```

### 1.3 Gauges (live DB reads, sampled on each Prometheus scrape)

These query the database on every scrape. Keep them cheap — no joins.

| Metric Name | Source Query | Threshold |
|-------------|-------------|-----------|
| `outbox.backlog` | `countByStatus(NEW) + countByStatus(PROCESSING)` | DEGRADED ≥ 500, DOWN ≥ 5 000 |
| `dlq.depth` | `DeadLetterMessageRepository.count()` | DEGRADED ≥ 1, DOWN ≥ 50 |
| `open.dispute.count` | `countByStatusIn([OPEN, UNDER_REVIEW])` | Alert if > 20 |
| `past_due.subscription.count` | `countBySubscriptionStatus(PAST_DUE)` | Alert if > 50 |
| `projection.lag.seconds` | Max `lagSeconds` across all projections | DEGRADED ≥ 300 s, DOWN ≥ 3 600 s |

---

## 2. Health Indicators

### Deep Health vs Liveness vs Readiness

| Endpoint | Purpose | Fails Pod? |
|----------|---------|-----------|
| `GET /actuator/health/liveness` | Is the JVM alive? | Yes — pod is killed |
| `GET /actuator/health/readiness` | Can the app accept traffic? | Yes — removed from load balancer |
| `GET /actuator/health` | Full composite health of all indicators | No — for monitoring only |
| `GET /ops/health/deep` | Rich observability report with SLOs | No — ops tool |

**Do not** put business-logic health indicators into the liveness probe. A slow
DLQ should not cause the pod to restart — it should alert the on-call engineer.

### Phase 21 Health Indicators

#### `OutboxLagHealthIndicator` (key: `outboxLag`)

| Condition | Status |
|-----------|--------|
| `failed = 0` AND `pending < 500` | `UP` |
| `failed > 0` OR `pending ≥ 500` | `UNKNOWN` (DEGRADED) |
| `pending ≥ 5 000` | `DOWN` |

#### `DlqDepthHealthIndicator` (key: `dlqDepth`)

| DLQ Depth | Status |
|-----------|--------|
| `= 0` | `UP` |
| `1 – 49` | `UNKNOWN` (DEGRADED) |
| `≥ 50` | `DOWN` |

#### `ProjectionLagHealthIndicator` (key: `projectionLag`)

| Max Lag | Status |
|---------|--------|
| `< 300 s` (5 min) | `UP` |
| `300 – 3 599 s` | `UNKNOWN` (DEGRADED) |
| `≥ 3 600 s` (1 hour) | `DOWN` |

An empty projection table (`lagSeconds = -1`) is treated as a fresh deployment
and counted as healthy.

#### `SchedulerStalenessHealthIndicator` (key: `schedulerStaleness`)

Uses a 30-minute expected interval. If any registered scheduler has not
completed a successful run within the last 30 minutes, status is `UNKNOWN`.

| Condition | Status |
|-----------|--------|
| All schedulers HEALTHY | `UP` |
| Any scheduler STALE | `UNKNOWN` |
| Some NEVER_RAN alongside HEALTHY | `UNKNOWN` |
| No history (fresh deployment) | `UP` |

---

## 3. Enhanced Deep Health API

### `GET /ops/health/deep`

Returns a single composite snapshot combining:
- Base operational counts (outbox, DLQ, webhooks, recon, integrity)
- Per-scheduler staleness
- Per-projection lag in seconds
- SLO evaluation summary

**Authorization:** `ROLE_ADMIN`

**Response shape:**
```json
{
  "overallStatus": "HEALTHY",
  "baseMetrics": { ... },
  "schedulers": [
    {
      "schedulerName": "subscription-renewal",
      "health": "HEALTHY",
      "lastSuccessAt": "2026-03-12T03:00:00Z",
      "lastRunAt": "2026-03-12T03:00:00Z",
      "lastRunStatus": "SUCCESS",
      "expectedInterval": "PT30M"
    }
  ],
  "projectionLagByName": {
    "payment_summary": 12,
    "ledger_balance": 45,
    "subscription_status": -1
  },
  "maxProjectionLagSeconds": 45,
  "sloStatus": [ ... ],
  "sloOverallStatus": "HEALTHY",
  "slosMeeting": 4,
  "slosAtRisk": 0,
  "slosBreached": 0,
  "checkedAt": "2026-03-12T10:00:00"
}
```

**`overallStatus` derivation:**
1. If `baseMetrics.overallStatus == DOWN` → `DOWN`
2. If any scheduler is `STALE` → `DEGRADED`
3. If `maxProjectionLagSeconds ≥ 3600` → `DOWN`
4. If `maxProjectionLagSeconds ≥ 300` → `DEGRADED`
5. If any SLO is `BREACHED` or `AT_RISK` → `DEGRADED`
6. Otherwise → `HEALTHY`

---

## 4. SLO Definitions

SLOs represent contractual operational guarantees. The following SLOs are
registered in `SloDefinition`:

| SLO ID | Name | Target | AT_RISK Below | Indicator |
|--------|------|--------|---------------|-----------|
| `payment.success.rate` | Payment Capture Success Rate | ≥ 95% | < 90% | Counter ratio |
| `refund.completion.rate` | Refund Completion Rate | ≥ 99.5% | < 97% | Counter ratio |
| `dunning.recovery.rate` | Dunning Recovery Rate | ≥ 60% | < 50% | Counter ratio |
| `outbox.dlq.ceiling` | DLQ Depth Ceiling | ≤ 100 entries | > 50 entries | DB count |
| `payment.capture.p95` | Payment Capture Latency | Mean ≤ 3 000 ms | > 5 000 ms | Timer mean |
| `webhook.delivery.p95` | Webhook Delivery Latency | Mean ≤ 5 000 ms | > 8 000 ms | Timer mean |

### `GET /ops/slo/status`

Returns the current evaluation of all 6 SLOs.

**SLO Status values:**
- `MEETING` — current metric is at or above the target
- `AT_RISK` — metric is between the at-risk threshold and the target (warn, don't page)
- `BREACHED` — metric has fallen below the target (**page the on-call**)
- `INSUFFICIENT_DATA` — counters/timers are at zero (likely pod just restarted)

**Response shape:**
```json
[
  {
    "sloId": "payment.success.rate",
    "name": "Payment Capture Success Rate",
    "targetPercent": 95.0,
    "currentValue": 97.8,
    "status": "MEETING",
    "window": "since last restart",
    "notes": "97.8% (489 successes / 500 total) since last restart",
    "evaluatedAt": "2026-03-12T10:00:00"
  }
]
```

### Alerting recommendations

```
# Alert: Page immediately
SLO status = BREACHED → P1 alert

# Alert: Investigate within 4 hours
SLO status = AT_RISK  → P3 alert

# Alert: Awareness only
SLO status = INSUFFICIENT_DATA for > 1 hour → P4 / data pipeline alert
```

---

## 5. Using `MetricsTagFactory`

Use `MetricsTagFactory` to avoid hardcoded string tags in service code:

```java
@Autowired MetricsTagFactory tagsFactory;
@Autowired MeterRegistry registry;

// Record a payment capture with outcome tag
Timer timer = registry.timer("payment.capture.latency",
    tagsFactory.paymentAttemptTags(merchantId, "stripe", "success"));
timer.record(() -> gateway.capture(request));

// Increment a counter with merchant tag
registry.counter("payment.success.total",
    tagsFactory.merchantAndOutcome(merchantId, "success")).increment();
```

**Note:** The pre-registered counters in `FinancialMetrics` do **not** carry
per-merchant tags, to avoid cardinality explosion. Add tags only at the call
site when the cardinality is bounded (e.g., gateway name, currency).

---

## 6. Prometheus Scraping

Metrics are exposed at:
- `GET /actuator/prometheus` — Prometheus scrape endpoint (text format)

Configure Prometheus to scrape every 15 seconds:
```yaml
scrape_configs:
  - job_name: firstclub-membership
    scrape_interval: 15s
    static_configs:
      - targets: ['localhost:8080']
    metrics_path: '/actuator/prometheus'
```

---

## 7. Runbook: SLO Breach Response

### Payment success rate BREACHED (< 95%)

1. Check `GET /ops/health/deep` for `baseMetrics.dlqCount` and `webhookFailedCount`
2. Check `GET /api/v2/admin/system/dlq` for failed payment events
3. Check gateway logs — may be a provider outage
4. If DLQ has entries: use `POST /ops/dlq/{id}/requeue` to retry after root-cause resolution
5. Review `payment.unknown.total` — elevated UNKNOWN rate means gateway timeout issues

### DLQ ceiling breached (> 100 entries)

1. `GET /api/v2/admin/system/dlq/summary` — group by failure category
2. For transient failures: `POST /api/v2/admin/system/dlq/{id}/retry` or `POST /ops/dlq/{id}/requeue`
3. For permanent failures: review event payload, fix the handler bug, then retry
4. See [02-dlq-retry-runbook.md](./02-dlq-retry-runbook.md) for the full procedure

### Projection lag DEGRADED (> 5 minutes)

1. Check that background scheduler is running: `GET /api/v2/admin/schedulers/health`
2. If scheduler is STALE: investigate job-lock contention in the `scheduler_executions` table
3. If lag is growing: trigger manual rebuild via `POST /api/v2/admin/projections/{name}/rebuild`
4. See [10-support-timeline.md](./10-support-timeline.md) for projection rebuild context
