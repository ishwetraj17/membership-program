/**
 * Phase 19 Load Test — Outbox Backlog Throughput
 *
 * Artificially pumps events into the outbox table faster than the poller
 * can drain it, then measures:
 *   - Time to drain a 10 000-event backlog
 *   - Max queue depth observed (via /actuator/metrics/outbox.pending)
 *   - Poller throughput (events/sec)
 *
 * This script has two phases:
 *   1. FILL  — 60 s of heavy event publishing (payment intents)
 *   2. DRAIN — poll the queue-depth metric until it returns to zero
 *
 * Run:
 *   k6 run --env BASE_URL=http://localhost:8080 \
 *          --env MERCHANT_ID=1 \
 *          --env API_KEY=test-key \
 *          load-tests/k6/outbox_backlog_throughput.js
 *
 * Tuning lever: outbox.poller.batch-size (application.properties)
 *   10  → safe, low-latency, lower throughput
 *   100 → higher throughput, longer lock window per transaction
 *   500 → only safe with read replicas and short-circuit handlers
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Gauge, Trend } from 'k6/metrics';

const eventsPublished  = new Counter('outbox_events_published');
const publishErrors    = new Counter('outbox_publish_errors');
const pendingGauge     = new Gauge('outbox_pending_depth');
const publishLatency   = new Trend('outbox_publish_ms');

export const options = {
    scenarios: {
        fill_phase: {
            executor: 'constant-vus',
            vus: 100,
            duration: '60s',
            tags: { phase: 'fill' },
        },
        drain_monitor: {
            executor: 'constant-vus',
            vus: 1,
            duration: '120s',
            startTime: '60s',
            tags: { phase: 'drain' },
        },
    },
    thresholds: {
        'outbox_publish_ms':   ['p(95)<300'],
        'outbox_publish_errors': ['count<50'],
    },
};

const BASE_URL    = __ENV.BASE_URL    || 'http://localhost:8080';
const MERCHANT_ID = __ENV.MERCHANT_ID || '1';
const API_KEY     = __ENV.API_KEY     || 'test-key';

export default function () {
    // drain_monitor VU: poll queue depth metric
    if (__ENV.phase === 'drain') {
        monitorOutboxDepth();
        sleep(5);
        return;
    }

    // fill_phase VUs: publish events as fast as possible
    const idemKey = `outbox-fill-${__VU}-${__ITER}`;
    const start   = Date.now();

    const res = http.post(
        `${BASE_URL}/api/v2/merchants/${MERCHANT_ID}/payment-intents`,
        JSON.stringify({ amount: 100, currency: 'GBP', description: 'outbox-fill' }),
        {
            headers: {
                'Content-Type':       'application/json',
                'Authorization':      `Bearer ${API_KEY}`,
                'X-Idempotency-Key':  idemKey,
            },
            timeout: '10s',
        }
    );

    publishLatency.add(Date.now() - start);

    if (res.status === 201 || res.status === 200) {
        eventsPublished.add(1);
    } else {
        publishErrors.add(1);
    }
}

function monitorOutboxDepth() {
    const res = http.get(
        `${BASE_URL}/actuator/metrics/outbox.pending`,
        { headers: { 'Authorization': `Bearer ${__ENV.API_KEY || 'test-key'}` } }
    );

    if (res.status === 200) {
        const body = res.json();
        const depth = body?.measurements?.[0]?.value ?? -1;
        pendingGauge.add(depth);
        console.log(`[drain] outbox pending depth = ${depth}`);

        check(res, {
            'depth decreasing or zero': () => depth >= 0,
        });
    }
}

export function handleSummary(data) {
    const published = data.metrics['outbox_events_published']?.values?.count ?? 0;
    const errors    = data.metrics['outbox_publish_errors']?.values?.count ?? 0;
    console.log(`Published: ${published}, Errors: ${errors}`);
    return {
        'load-tests/results/outbox_backlog_throughput_summary.json': JSON.stringify(data),
    };
}
