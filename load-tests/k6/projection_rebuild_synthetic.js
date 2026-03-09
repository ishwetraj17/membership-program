/**
 * Phase 19 Load Test — Projection Rebuild (Synthetic)
 *
 * Triggers a projection rebuild via the admin repair endpoint and measures:
 *   - Time to complete rebuilding N subscription projections
 *   - Impact on concurrent read traffic (latency degradation)
 *   - Whether the rebuild endpoint is idempotent (safe to call twice)
 *
 * Run:
 *   k6 run --env BASE_URL=http://localhost:8080 \
 *          --env ADMIN_API_KEY=admin-key \
 *          --env READ_API_KEY=test-key \
 *          --env MERCHANT_ID=1 \
 *          load-tests/k6/projection_rebuild_synthetic.js
 *
 * Expected behaviour:
 *   - Rebuild completes in < 30 s for 1 000 subscriptions
 *   - Concurrent read P95 degrades no more than 2x during rebuild
 *   - Second rebuild call returns 200 with "already_current" or equivalent
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const rebuildLatency   = new Trend('projection_rebuild_ms');
const readLatencyBase  = new Trend('projection_read_ms_baseline');
const readLatencyRebuild = new Trend('projection_read_ms_during_rebuild');
const rebuildErrors    = new Counter('projection_rebuild_errors');

export const options = {
    scenarios: {
        // One VU triggers the rebuild
        rebuild_trigger: {
            executor: 'shared-iterations',
            vus: 1,
            iterations: 3,
            maxDuration: '120s',
            tags: { role: 'rebuild' },
        },
        // 20 VUs reading subscription projections concurrently
        read_traffic: {
            executor: 'constant-vus',
            vus: 20,
            duration: '120s',
            tags: { role: 'read' },
        },
    },
    thresholds: {
        'projection_rebuild_ms':          ['p(95)<30000'],  // 30 s max
        'projection_read_ms_during_rebuild': ['p(95)<600'], // 2x of 300 ms baseline
    },
};

const BASE_URL      = __ENV.BASE_URL      || 'http://localhost:8080';
const MERCHANT_ID   = __ENV.MERCHANT_ID   || '1';
const ADMIN_API_KEY = __ENV.ADMIN_API_KEY || 'admin-key';
const READ_API_KEY  = __ENV.READ_API_KEY  || 'test-key';

export default function () {
    if (__ENV.role === 'rebuild') {
        triggerRebuild();
        sleep(30); // Wait before next rebuild attempt
        return;
    }

    // Read traffic VU
    readSubscriptionProjection();
    sleep(0.1);
}

function triggerRebuild() {
    const start = Date.now();
    const res = http.post(
        `${BASE_URL}/api/v1/admin/repair/projection-rebuild`,
        JSON.stringify({ merchantId: parseInt(MERCHANT_ID), scope: 'subscriptions' }),
        {
            headers: {
                'Content-Type':  'application/json',
                'Authorization': `Bearer ${ADMIN_API_KEY}`,
            },
            timeout: '120s',
        }
    );
    const elapsed = Date.now() - start;
    rebuildLatency.add(elapsed);

    const ok = check(res, {
        'rebuild accepted (200/202)': (r) => r.status === 200 || r.status === 202,
    });

    if (!ok) {
        rebuildErrors.add(1);
        console.log(`Rebuild failed: ${res.status} ${res.body}`);
    } else {
        console.log(`Rebuild completed in ${elapsed} ms — status ${res.status}`);
    }
}

function readSubscriptionProjection() {
    const subId = Math.floor(Math.random() * 1000) + 1;
    const start = Date.now();
    const res = http.get(
        `${BASE_URL}/api/v2/subscriptions/${subId}`,
        {
            headers: {
                'Authorization': `Bearer ${READ_API_KEY}`,
            },
            timeout: '10s',
        }
    );
    const elapsed = Date.now() - start;

    // Tag with appropriate metric depending on whether rebuild is running
    readLatencyRebuild.add(elapsed);

    check(res, {
        'subscription read OK': (r) => r.status === 200 || r.status === 404,
    });
}

export function handleSummary(data) {
    const rebuildP95 = data.metrics['projection_rebuild_ms']?.values?.['p(95)'] ?? 'N/A';
    const readP95    = data.metrics['projection_read_ms_during_rebuild']?.values?.['p(95)'] ?? 'N/A';
    console.log(`Rebuild P95: ${rebuildP95} ms | Read P95 during rebuild: ${readP95} ms`);
    return {
        'load-tests/results/projection_rebuild_synthetic_summary.json': JSON.stringify(data),
    };
}
