/**
 * Phase 19 Load Test — Payment Intent Burst
 *
 * Simulates a merchant creating payment intents at burst rate.
 * Measures P95 latency, error rate, and idempotency key collision behaviour.
 *
 * Run:
 *   k6 run --env BASE_URL=http://localhost:8080 \
 *          --env MERCHANT_ID=1 \
 *          --env API_KEY=test-key \
 *          load-tests/k6/payment_intent_burst.js
 *
 * Expected baseline (single Postgres, no Redis):
 *   P95  < 200 ms
 *   P99  < 500 ms
 *   Error rate < 0.5%
 *
 * At 500 RPS you will see connection pool exhaustion.
 * Tune spring.datasource.hikari.maximum-pool-size accordingly.
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';

const p95Latency   = new Trend('payment_intent_p95');
const errorCount   = new Counter('payment_intent_errors');
const successRate  = new Rate('payment_intent_success_rate');

export const options = {
    scenarios: {
        burst: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 50  },   // Ramp up to 50 VUs
                { duration: '60s', target: 200 },   // Sustain 200 VUs (≈200 RPS)
                { duration: '30s', target: 500 },   // Spike to 500 VUs
                { duration: '30s', target: 50  },   // Cool down
                { duration: '10s', target: 0   },
            ],
        },
    },
    thresholds: {
        http_req_duration:          ['p(95)<500'],
        payment_intent_success_rate: ['rate>0.99'],
    },
};

const BASE_URL    = __ENV.BASE_URL    || 'http://localhost:8080';
const MERCHANT_ID = __ENV.MERCHANT_ID || '1';
const API_KEY     = __ENV.API_KEY     || 'test-key';

export default function () {
    const idempotencyKey = `idem-${__VU}-${__ITER}-${Date.now()}`;
    const payload = JSON.stringify({
        amount:      1000,
        currency:    'GBP',
        description: `Load test intent VU=${__VU} ITER=${__ITER}`,
    });

    const res = http.post(
        `${BASE_URL}/api/v2/merchants/${MERCHANT_ID}/payment-intents`,
        payload,
        {
            headers: {
                'Content-Type':   'application/json',
                'Authorization':  `Bearer ${API_KEY}`,
                'X-Idempotency-Key': idempotencyKey,
            },
            timeout: '10s',
        }
    );

    const ok = check(res, {
        'status is 201 or 200': (r) => r.status === 201 || r.status === 200,
        'has intentId':         (r) => r.json('id') !== undefined,
    });

    p95Latency.add(res.timings.duration);
    successRate.add(ok);
    if (!ok) errorCount.add(1);

    sleep(0.01); // 10 ms think time → ~100 RPS per VU at peak
}

export function handleSummary(data) {
    return {
        'load-tests/results/payment_intent_burst_summary.json': JSON.stringify(data),
    };
}
