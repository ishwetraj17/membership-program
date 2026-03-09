package com.firstclub.platform.idempotency;

import java.time.LocalDateTime;

/**
 * Immutable DTO representing a cached idempotency response — used both for the
 * Redis hot-path and when replaying from the DB authoritative record.
 *
 * <p>Replaces {@link IdempotencyResponseEnvelope} for all Phase-4 code paths.
 * {@code originalAt} is the timestamp when the original request completed,
 * surfaced as the {@code X-Idempotency-Original-At} response header.
 */
public record CachedIdempotencyResponse(
        /** SHA-256(method + path + body) — validated on replay. */
        String requestHash,
        /** "{HTTP_METHOD}:{url-template}" — validated on replay. */
        String endpointSignature,
        /** Original HTTP status code that must be replayed verbatim. */
        int statusCode,
        /** Serialised response body (JSON or otherwise). */
        String responseBody,
        /** Content-Type of the original response. */
        String contentType,
        /**
         * Wall-clock time when the original request completed.
         * Null when loaded from legacy (pre-Phase-4) Redis entries.
         */
        LocalDateTime originalAt) {
}
