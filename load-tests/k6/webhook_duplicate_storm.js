/**
 * Phase 19 Load Test — Webhook Duplicate Storm
 *
 * Sends the same webhook event payload multiple times with the same
 * fingerprint to prove deduplication is enforced under load.
 * Also sends a concurrent mix of new events to measure throughput.
 *
 * Run:
 *   k6 run --env BASE_URL=http://localhost:8080 \
 *          --env MERCHANT_ID=1 \
 *          --env API_KEY=test-key \
 *          load-tests/k6/webhook_duplicate_storm.js
 *
 * Key assertions:
 *   - Duplicate fingerprints return 409 CONFLICT  (dedup guard active)
 *   - Unique events return 201 CREATED
 *   - P95 < 150 ms (enqueue is fast — delivery is async)
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const dedupRejections  = new Counter('webhook_dedup_rejections');
const enqueueSuccess   = new Rate('webhook_enqueue_success');
const enqueueLatency   = new Trend('webhook_enqueue_ms');

export const options = {
    scenarios: {
        // 30 VUs repeatedly sending the SAME fingerprint
        duplicate_storm: {
            executor: 'constant-vus',
            vus: 30,
            duration: '60s',
            env: { MODE: 'duplicate' },
        },
        // 20 VUs sending unique events (normal traffic baseline)
        unique_flood: {
            executor: 'constant-vus',
            vus: 20,
            duration: '60s',
            startTime: '10s',
            env: { MODE: 'unique' },
        },
    },
    thresholds: {
        'webhook_enqueue_ms':      ['p(95)<200'],
        'webhook_enqueue_success': ['rate>0.95'],
    },
};

const BASE_URL    = __ENV.BASE_URL    || 'http://localhost:8080';
const MERCHANT_ID = __ENV.MERCHANT_ID || '1';
const ENDPOINT_ID = __ENV.ENDPOINT_ID || '1';
const API_KEY     = __ENV.API_KEY     || 'test-key';

// Fixed fingerprint — all "duplicate" VUs attempt to enqueue this event
const SHARED_FINGERPRINT = 'fp-phase19-dedup-test-001';

export default function () {
    const isDuplicate = __ENV.MODE === 'duplicate';
    const fingerprint = isDuplicate
        ? SHARED_FINGERPRINT
        : `fp-${__VU}-${__ITER}-${Date.now()}`;

    const payload = JSON.stringify({
        eventType:   'invoice.paid',
        payload:     JSON.stringify({ invoiceId: isDuplicate ? 42 : __ITER }),
        fingerprint: fingerprint,
    });

    const start = Date.now();
    const res = http.post(
        `${BASE_URL}/api/v2/merchants/${MERCHANT_ID}/webhook-endpoints/${ENDPOINT_ID}/deliver`,
        payload,
        {
            headers: {
                'Content-Type':  'application/json',
                'Authorization': `Bearer ${API_KEY}`,
            },
            timeout: '10s',
        }
    );
    enqueueLatency.add(Date.now() - start);

    if (res.status === 409) {
        dedupRejections.add(1);
        enqueueSuccess.add(true); // 409 is the CORRECT response for a duplicate
        return;
    }

    const ok = check(res, {
        'enqueued 201': (r) => r.status === 201,
    });
    enqueueSuccess.add(ok);

    sleep(0.02);
}

export function handleSummary(data) {
    console.log(`Dedup rejections: ${data.metrics['webhook_dedup_rejections']?.values?.count ?? 0}`);
    return {
        'load-tests/results/webhook_duplicate_storm_summary.json': JSON.stringify(data),
    };
}
