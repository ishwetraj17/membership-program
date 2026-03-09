package com.firstclub.platform.errors;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Thrown when a second request arrives for an operation that is already being
 * processed by a concurrent thread or worker, and the platform cannot safely
 * serve both operations in parallel.
 *
 * <h3>Distinction from {@link IdempotencyConflictException}</h3>
 * <ul>
 *   <li>{@code RequestInFlightException} — operation-level mutual exclusion.
 *       Two separate callers are trying to drive the same entity through the
 *       same state transition simultaneously (e.g., two threads capturing the
 *       same payment intent).</li>
 *   <li>{@code IdempotencyConflictException} — key-level conflict.
 *       The same client replayed an {@code Idempotency-Key} either with a
 *       different body or while the original is still being processed.</li>
 * </ul>
 *
 * <h3>Common triggers</h3>
 * <ul>
 *   <li>Two concurrent capture requests for the same {@code PaymentIntent}.</li>
 *   <li>Two outbox workers racing to process the same outbox row before the
 *       row-level lock is acquired.</li>
 *   <li>A manual operator action and an automated reconciliation job both
 *       trying to transition the same subscription.</li>
 * </ul>
 *
 * <h3>HTTP mapping</h3>
 * Maps to HTTP 409 Conflict.  The caller should retry after the in-flight
 * operation completes.
 */
public final class RequestInFlightException extends BaseDomainException {

    /**
     * @param operationType  human-readable name of the operation
     *                       (e.g., {@code "CapturePayment"})
     * @param entityId       entity identifier as string
     */
    public RequestInFlightException(String operationType, String entityId) {
        super("REQUEST_IN_FLIGHT",
              "Operation '" + operationType + "' is already in flight for "
                      + "entity id=" + entityId
                      + ". Retry after the current operation completes.",
              HttpStatus.CONFLICT,
              Map.of("operationType", operationType,
                     "entityId",      entityId));
    }
}
