package com.firstclub.platform.errors;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Thrown when a request is rejected due to an {@code Idempotency-Key} conflict.
 *
 * <h3>Conflict types</h3>
 * <ul>
 *   <li>{@link ConflictType#BODY_MISMATCH} — the same key was sent with a
 *       different request body. The original semantics are preserved; the
 *       new request is rejected with HTTP 409.</li>
 *   <li>{@link ConflictType#IN_FLIGHT} — the same key is currently being
 *       processed by a concurrent request. Retry after the in-flight
 *       request completes.</li>
 *   <li>{@link ConflictType#ENDPOINT_MISMATCH} — the same key was originally
 *       used on a different endpoint. Keys are endpoint-scoped.</li>
 * </ul>
 *
 * <h3>Relation to IdempotencyFilter</h3>
 * The {@link com.firstclub.platform.idempotency.IdempotencyFilter} handles
 * HTTP-layer conflicts by writing 409 responses directly (no exception thrown).
 * This exception is for service-layer idempotency guards that execute after
 * the filter — e.g., a payment capture service that re-checks idempotency
 * at the business operation level.
 *
 * <p>Maps to HTTP 409 Conflict.
 */
public final class IdempotencyConflictException extends BaseDomainException {

    /**
     * Type of idempotency conflict.
     */
    public enum ConflictType {
        /** Same key, different request body (signature mismatch). */
        BODY_MISMATCH,
        /** Same key, request is already being processed by a concurrent thread. */
        IN_FLIGHT,
        /** Same key was used on a different endpoint. */
        ENDPOINT_MISMATCH
    }

    private final ConflictType conflictType;

    public IdempotencyConflictException(ConflictType conflictType,
                                        String idempotencyKey,
                                        String detail) {
        super("IDEMPOTENCY_" + conflictType.name(),
              "Idempotency conflict [" + conflictType + "] for key=" + idempotencyKey + ": " + detail,
              HttpStatus.CONFLICT,
              Map.of("idempotencyKey", idempotencyKey,
                     "conflictType", conflictType.name()));
        this.conflictType = conflictType;
    }

    public ConflictType getConflictType() {
        return conflictType;
    }

    // ── Factory helpers ──────────────────────────────────────────────────────

    /** Same key was sent with a different request body. */
    public static IdempotencyConflictException bodyMismatch(String key) {
        return new IdempotencyConflictException(
                ConflictType.BODY_MISMATCH, key,
                "Request body fingerprint does not match the original request for this key.");
    }

    /** Same key is currently being processed by a concurrent request. */
    public static IdempotencyConflictException inFlight(String key) {
        return new IdempotencyConflictException(
                ConflictType.IN_FLIGHT, key,
                "A concurrent request with this key is already in progress. Retry after completion.");
    }

    /** Same key was used on a different endpoint. */
    public static IdempotencyConflictException endpointMismatch(String key, String originalEndpoint) {
        return new IdempotencyConflictException(
                ConflictType.ENDPOINT_MISMATCH, key,
                "Key was originally issued on endpoint: " + originalEndpoint);
    }
}
