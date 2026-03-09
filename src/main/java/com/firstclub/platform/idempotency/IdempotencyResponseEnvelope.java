package com.firstclub.platform.idempotency;

/**
 * Immutable envelope stored in Redis as the cached response for a completed
 * idempotency key.
 *
 * <p>Stored at Redis key {@code {env}:firstclub:idem:resp:{merchantId}:{key}}.
 * The TTL is derived from the original
 * {@link com.firstclub.platform.idempotency.annotation.Idempotent#ttlHours()}.
 *
 * <p>On a Redis cache HIT the filter validates both {@link #requestHash()} and
 * {@link #endpointSignature()} before replaying the stored response, ensuring
 * that a cached entry for a different payload or endpoint is never replayed.
 *
 * <p><b>Only 2xx and 4xx responses are cached.</b>  5xx responses are
 * transient errors; the client is expected to retry and the upstream operation
 * should then succeed.
 */
public record IdempotencyResponseEnvelope(
        /** SHA-256(method + path + body) of the original request. */
        String requestHash,
        /** "{HTTP_METHOD}:{url-template}" e.g. "POST:/api/v2/subscriptions". */
        String endpointSignature,
        /** HTTP status code of the original response. */
        int statusCode,
        /** Serialised JSON response body. */
        String responseBody,
        /** Content-Type of the original response (usually "application/json"). */
        String contentType) {
}
