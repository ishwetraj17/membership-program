# Phase 19 — Load Tests

Performance and failure-injection load test scripts for the FirstClub Membership Platform.

## Prerequisites

```bash
# Install k6  (macOS)
brew install k6

# Or via Docker
docker pull grafana/k6
```

## Directory structure

```
load-tests/
├── k6/
│   ├── payment_intent_burst.js         # Payment intent creation at scale
│   ├── payment_confirm_burst.js        # Full create→confirm hot path
│   ├── webhook_duplicate_storm.js      # Dedup guard under duplicate flood
│   ├── outbox_backlog_throughput.js    # Outbox fill + drain measurement
│   └── projection_rebuild_synthetic.js # Projection rebuild impact on reads
├── results/                            # Auto-created by k6 on run (gitignored)
└── README.md
```

## Quick start

```bash
# Start the app in a test environment first
./mvnw spring-boot:run -Dspring-boot.run.profiles=test

# Run payment intent burst
k6 run --env BASE_URL=http://localhost:8080 \
       --env MERCHANT_ID=1 \
       --env API_KEY=test-key \
       load-tests/k6/payment_intent_burst.js

# Run the full confirm flow
k6 run --env BASE_URL=http://localhost:8080 \
       --env MERCHANT_ID=1 \
       --env API_KEY=test-key \
       load-tests/k6/payment_confirm_burst.js

# Webhook dedup storm
k6 run --env BASE_URL=http://localhost:8080 \
       --env MERCHANT_ID=1 \
       --env API_KEY=test-key \
       load-tests/k6/webhook_duplicate_storm.js

# Outbox backlog throughput (requires /actuator/metrics enabled)
k6 run --env BASE_URL=http://localhost:8080 \
       --env MERCHANT_ID=1 \
       --env API_KEY=test-key \
       load-tests/k6/outbox_backlog_throughput.js

# Projection rebuild impact
k6 run --env BASE_URL=http://localhost:8080 \
       --env MERCHANT_ID=1 \
       --env ADMIN_API_KEY=admin-key \
       --env READ_API_KEY=test-key \
       load-tests/k6/projection_rebuild_synthetic.js
```

## Baseline targets (single-node, dev PostgreSQL)

| Script                        | P95 target  | P99 target  | Error rate |
|-------------------------------|-------------|-------------|------------|
| `payment_intent_burst`        | < 200 ms    | < 500 ms    | < 0.5%     |
| `payment_confirm_burst`       | < 300 ms    | < 800 ms    | < 1%       |
| `webhook_duplicate_storm`     | < 150 ms    | < 400 ms    | < 0.5%     |
| `outbox_backlog_throughput`   | < 300 ms    | < 600 ms    | < 1%       |
| `projection_rebuild_synthetic`| < 30 s for rebuild | —   | 0%         |

> **Note:** These are *development* baselines on a single PostgreSQL instance with no read replicas.
> Targets narrow significantly once Redis caching and connection pooling are tuned.
> See `/docs/performance/01-bottlenecks.md` and `/docs/architecture/08-scaling-path.md`.

## Failure injection

The unit-level failure injection tests live in:
```
src/test/java/com/firstclub/performance/Phase19FailureInjectionTest.java
```

These complement the k6 scripts by proving failure paths work correctly without
needing a live environment:
1. Redis unavailable → idempotency falls back to PostgreSQL
2. DB failure during outbox processing → event marked FAILED, written to DLQ
3. Duplicate optimistic lock → ObjectOptimisticLockingFailureException surfaces
4. Stale lock → TransientDataAccessException propagated
5. Scheduler double-fire → second acquire returns false immediately
6. Webhook consecutive failures → endpoint auto-disabled after threshold

## Interpreting results

After each k6 run the summary JSON is written to `load-tests/results/`.
Key metrics to inspect:

```
http_req_duration........: avg=83.22ms  min=12ms  p(90)=190ms  p(95)=230ms  p(99)=480ms
http_reqs................: 41230   total (≈227/s)
http_req_failed..........: 0.23%
```

When P95 exceeds target:
1. Check Postgres `pg_stat_activity` for blocking queries.
2. Inspect `spring.datasource.hikari.maximum-pool-size` — default 10 is too low.
3. Enable Redis and re-run: should drop P95 by ~40% on idempotency-heavy paths.
4. See `/docs/performance/04-load-test-notes.md` for post-test SQL audit queries.
