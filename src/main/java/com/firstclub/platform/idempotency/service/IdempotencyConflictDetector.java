package com.firstclub.platform.idempotency.service;

import com.firstclub.platform.idempotency.IdempotencyKeyEntity;
import com.firstclub.platform.idempotency.IdempotencyStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Detects conflicts between an incoming idempotent request and an existing
 * idempotency record.
 *
 * <h3>Conflict hierarchy</h3>
 * <p>Checks are performed in this order to ensure the most informative error:
 * <ol>
 *   <li>{@link ConflictKind#ENDPOINT_MISMATCH} — key used on a different URL → HTTP 422</li>
 *   <li>{@link ConflictKind#BODY_MISMATCH} — same endpoint, different body → HTTP 422</li>
 *   <li>{@link ConflictKind#IN_FLIGHT} — key matches but first request is still processing → HTTP 409</li>
 * </ol>
 * <p>If none of the above apply, the request is a clean duplicate and may be
 * replayed or (if the record is {@link IdempotencyStatus#PROCESSING}) must wait.
 */
@Component
public class IdempotencyConflictDetector {

    /** Characterises the type of conflict found between the incoming and stored record. */
    public enum ConflictKind {
        /** Same key, different endpoint. Clients must use a new Idempotency-Key. */
        ENDPOINT_MISMATCH,
        /** Same key and endpoint, different request body. Clients must use a new Idempotency-Key. */
        BODY_MISMATCH,
        /** Same key, same body, but the original request is still being processed. */
        IN_FLIGHT
    }

    /** Encapsulates a detected conflict with its kind and a human-readable message. */
    public record ConflictResult(ConflictKind kind, String message) {}

    /**
     * Checks for conflicts between an incoming request and an existing record.
     *
     * <p>Endpoint signature is checked before body hash so that cross-endpoint
     * key reuse is always surfaced as {@link ConflictKind#ENDPOINT_MISMATCH} rather
     * than silently appearing as a body mismatch.
     *
     * @param incomingHash      SHA-256(method + path + body) of the incoming request
     * @param incomingEndpoint  "{METHOD}:{url-template}" of the incoming request
     * @param record            existing idempotency record
     * @return empty if no conflict (incoming is a clean duplicate); non-empty if conflict found
     */
    public Optional<ConflictResult> detect(String incomingHash,
                                            String incomingEndpoint,
                                            IdempotencyKeyEntity record) {
        // 1. Endpoint mismatch → 422 (skip if record has no stored signature — legacy record)
        if (record.getEndpointSignature() != null
                && !incomingEndpoint.equals(record.getEndpointSignature())) {
            return Optional.of(new ConflictResult(
                    ConflictKind.ENDPOINT_MISMATCH,
                    "Idempotency-Key was previously used on a different endpoint: "
                            + record.getEndpointSignature()));
        }

        // 2. Body mismatch → 422 (skip if record has no stored hash — legacy record)
        if (record.getRequestHash() != null
                && !incomingHash.equals(record.getRequestHash())) {
            return Optional.of(new ConflictResult(
                    ConflictKind.BODY_MISMATCH,
                    "Idempotency-Key was previously used with a different request body"));
        }

        // 3. In-flight → 409 (uses entity's null-safe isProcessing() for legacy records)
        if (record.isProcessing()) {
            return Optional.of(new ConflictResult(
                    ConflictKind.IN_FLIGHT,
                    "A request with this Idempotency-Key is already being processed"));
        }

        // No conflict — safe to replay (COMPLETED, FAILED_RETRYABLE, FAILED_FINAL, EXPIRED)
        return Optional.empty();
    }
}
