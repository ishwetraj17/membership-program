package com.firstclub.platform.idempotency;

/**
 * Lifecycle states for an idempotency key record.
 *
 * <pre>
 *   (new request)
 *        │
 *        ▼
 *   PROCESSING ─── request completes normally ──────────▶ COMPLETED
 *        │                                                    │
 *        │ stuck threshold exceeded                           │ (replayed)
 *        ▼                                                    ▼
 *  FAILED_RETRYABLE ──── (client retries with same key) ──▶ (re-runs safely)
 *        │
 *        │ 5xx terminal error
 *        ▼
 *  FAILED_FINAL ─── (client must use new Idempotency-Key)
 *
 *   EXPIRED ← cleanup scheduler marks records past expires_at
 * </pre>
 */
public enum IdempotencyStatus {

    /**
     * Placeholder created; the originating request is still in-flight.
     * Concurrent duplicate requests receive 409.
     */
    PROCESSING,

    /**
     * Request completed successfully (any 2xx–4xx response).
     * Subsequent duplicate requests receive a replayed response.
     */
    COMPLETED,

    /**
     * Request was interrupted (e.g., server crash) before completion.
     * The cleanup scheduler moves stuck PROCESSING rows here after the
     * configurable threshold.  Clients may retry safely with the
     * <em>same</em> Idempotency-Key.
     */
    FAILED_RETRYABLE,

    /**
     * Request failed with a terminal business error.  Retrying with the
     * same Idempotency-Key replays the original failure response.
     */
    FAILED_FINAL,

    /**
     * Record TTL elapsed.  The cleanup scheduler marks or deletes
     * these rows.  The key is effectively recycled.
     */
    EXPIRED
}
