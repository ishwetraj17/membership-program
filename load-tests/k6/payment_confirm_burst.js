/**
 * Phase 19 Load Test — Payment Confirm Burst
 *
 * Confirms payment intents at scale.  Each VU creates an intent, then
 * immediately confirms it — this exercises the full payment capture hot path:
 *   POST /payment-intents → POST /payment-intents/{id}/confirm
 *
 * Run:
 *   k6 run --env BASE_URL=http://localhost:8080 \
 *          --env MERCHANT_ID=1 \
 *          --env API_KEY=test-key \
 *          load-tests/k6/payment_confirm_burst.js
 *
 * Expected baseline:
 *   P95  < 300 ms  (includes two DB writes + outbox publish)
 *   P99  < 800 ms
 *   Error rate < 1%
 */

import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';

const confirmLatency  = new Trend('payment_confirm_duration_ms');
const confirmErrors   = new Counter('payment_confirm_errors');
const confirmSuccess  = new Rate('payment_confirm_success_rate');

export const options = {
    scenarios: {
        confirm_burst: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 50  },
                { duration: '90s', target: 150 },
                { duration: '30s', target: 0   },
            ],
        },
    },
    thresholds: {
        'payment_confirm_duration_ms': ['p(95)<800'],
        'payment_confirm_success_rate': ['rate>0.99'],
    },
};

const BASE_URL    = __ENV.BASE_URL    || 'http://localhost:8080';
const MERCHANT_ID = __ENV.MERCHANT_ID || '1';
const API_KEY     = __ENV.API_KEY     || 'test-key';

const HEADERS = (idempotencyKey) => ({
    'Content-Type':        'application/json',
    'Authorization':       `Bearer ${API_KEY}`,
    'X-Idempotency-Key':   idempotencyKey,
});

export default function () {
    // Step 1 — Create intent
    const createKey = `create-${__VU}-${__ITER}`;
    const createRes = http.post(
        `${BASE_URL}/api/v2/merchants/${MERCHANT_ID}/payment-intents`,
        JSON.stringify({ amount: 500, currency: 'GBP', description: 'confirm-test' }),
        { headers: HEADERS(createKey), timeout: '10s' }
    );

    if (createRes.status !== 201 && createRes.status !== 200) {
        confirmErrors.add(1);
        return; // can't confirm what wasn't created
    }

    const intentId = createRes.json('id');
    if (!intentId) {
        confirmErrors.add(1);
        return;
    }

    // Step 2 — Confirm the intent
    const confirmKey = `confirm-${__VU}-${__ITER}`;
    const confirmStart = Date.now();
    const confirmRes = http.post(
        `${BASE_URL}/api/v2/merchants/${MERCHANT_ID}/payment-intents/${intentId}/confirm`,
        JSON.stringify({ paymentMethodId: 'pm-test-visa' }),
        { headers: HEADERS(confirmKey), timeout: '15s' }
    );
    confirmLatency.add(Date.now() - confirmStart);

    const ok = check(confirmRes, {
        'confirm status 200':    (r) => r.status === 200,
        'status is CAPTURED':    (r) => r.json('status') === 'CAPTURED',
    });

    confirmSuccess.add(ok);
    if (!ok) confirmErrors.add(1);

    sleep(0.05);
}

export function handleSummary(data) {
    return {
        'load-tests/results/payment_confirm_burst_summary.json': JSON.stringify(data),
    };
}
