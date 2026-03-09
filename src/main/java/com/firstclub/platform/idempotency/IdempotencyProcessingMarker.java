package com.firstclub.platform.idempotency;

/**
 * Immutable marker stored in Redis as the payload of an in-flight processing
 * lock for an idempotency key.
 *
 * <p>Stored at Redis key {@code {env}:firstclub:idem:lock:{merchantId}:{key}}
 * with a short TTL (30 s).  The NX (set-if-not-exists) semantics ensure that
 * only the first concurrent request acquires the lock; subsequent duplicates
 * that arrive before the first request completes are rejected immediately with
 * {@code 409 IDEMPOTENCY_IN_PROGRESS}.
 *
 * <p>The lock is released explicitly after the response is stored.  If the
 * holder crashes, the 30 s TTL provides a self-healing boundary.
 */
public record IdempotencyProcessingMarker(
        /** SHA-256(method + path + body) recorded at lock-acquisition time. */
        String requestHash,
        /** "{HTTP_METHOD}:{url-template}" recorded at lock-acquisition time. */
        String endpointSignature,
        /** ISO-8601 timestamp when the lock was acquired. */
        String lockedAt,
        /** Unique ID identifying the specific request instance that holds the lock. */
        String requestId) {
}
