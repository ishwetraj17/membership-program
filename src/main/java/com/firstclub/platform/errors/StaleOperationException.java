package com.firstclub.platform.errors;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Thrown when an operation is rejected because the entity state it acted upon
 * is stale — the underlying data was modified by another operation after this
 * one was scheduled or computed.
 *
 * <h3>When to throw this vs. {@link IdempotencyConflictException}</h3>
 * <ul>
 *   <li>{@code StaleOperationException} — the entity state precondition was
 *       wrong at fire time (e.g., dunning fired for an already-paid invoice).</li>
 *   <li>{@code IdempotencyConflictException} — the same
 *       {@code Idempotency-Key} was sent twice with conflicting payloads or
 *       while the original is still in flight.</li>
 * </ul>
 *
 * <h3>Common triggers</h3>
 * <ul>
 *   <li>A scheduled dunning attempt fires but the invoice was already paid.</li>
 *   <li>An outbox handler processes a {@code payment.settled} event for a
 *       payment that was already cancelled.</li>
 *   <li>A bulk reconciliation job targets rows that another job reconciled
 *       first.</li>
 * </ul>
 *
 * <h3>HTTP mapping</h3>
 * Maps to HTTP 409 Conflict — the request is syntactically and
 * authorisation-wise valid, but the current entity state makes it a
 * contradiction.  Callers should determine whether the operation is a safe
 * no-op or requires investigation.
 */
public final class StaleOperationException extends BaseDomainException {

    /**
     * @param entityType    human-readable entity type (e.g., {@code "Invoice"})
     * @param entityId      entity identifier as string
     * @param expectedState state the caller assumed the entity was in
     * @param actualState   state the entity was actually found in
     */
    public StaleOperationException(String entityType,
                                    Object entityId,
                                    String expectedState,
                                    String actualState) {
        super("STALE_OPERATION",
              "Stale operation on " + entityType + " id=" + entityId
                      + ": expected state='" + expectedState
                      + "' but found='" + actualState + "'.",
              HttpStatus.CONFLICT,
              Map.of("entityType",    entityType,
                     "entityId",      String.valueOf(entityId),
                     "expectedState", expectedState,
                     "actualState",   actualState));
    }
}
