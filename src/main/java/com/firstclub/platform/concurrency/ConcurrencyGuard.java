package com.firstclub.platform.concurrency;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.function.Supplier;

/**
 * Utility methods for applying and translating concurrency guards.
 *
 * <p>Provides two main services:
 * <ol>
 *   <li><b>OCC translation:</b> wraps a supplier so that Hibernate's
 *       {@link ObjectOptimisticLockingFailureException} is translated to a
 *       {@link ConcurrencyConflictException} with structured metadata before
 *       it can reach the generic 500 handler.</li>
 *   <li><b>Conflict logging:</b> ensures every concurrency exception is logged
 *       with the full MDC context (requestId, correlationId, merchantId) so
 *       operators can correlate conflicts to request traces.</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   // Wrap any optimistic-lock-risky save:
 *   SubscriptionV2 updated = ConcurrencyGuard.withOptimisticLock(
 *       "SubscriptionV2", sub.getId(),
 *       () -> subscriptionRepository.save(sub));
 * }</pre>
 */
@Slf4j
public final class ConcurrencyGuard {

    private ConcurrencyGuard() { }

    // ── Optimistic lock translation ───────────────────────────────────────────

    /**
     * Executes {@code action} and translates any
     * {@link ObjectOptimisticLockingFailureException} thrown by Hibernate into a
     * {@link ConcurrencyConflictException} with entity-level context.
     *
     * <p>The exception is always logged with MDC before re-throw so that every
     * concurrency conflict appears in structured logs with request context.
     *
     * @param <T>        return type of the action
     * @param entityType human-readable entity type name (e.g. {@code "SubscriptionV2"})
     * @param entityId   the entity's primary key (used in the log and exception message)
     * @param action     the repository save or other JPA write to execute
     * @return the action's return value
     * @throws ConcurrencyConflictException if an optimistic lock conflict is detected
     */
    public static <T> T withOptimisticLock(String entityType, Object entityId,
                                            Supplier<T> action) {
        try {
            return action.get();
        } catch (ObjectOptimisticLockingFailureException ex) {
            logConflict(entityType, entityId, "OPTIMISTIC_LOCK", ex.getMessage());
            throw ConcurrencyConflictException.optimisticLock(entityType, entityId);
        }
    }

    /**
     * Executes {@code action} and translates both {@link ObjectOptimisticLockingFailureException}
     * and {@link DataIntegrityViolationException} into a
     * {@link ConcurrencyConflictException}.
     *
     * <p>Use this variant when a DB unique constraint is the last-resort guard
     * (e.g. payment attempt numbering, subscription duplicate create).
     * The raw constraint violation is translated to a clean 409 rather than a 500.
     *
     * @param entityType  human-readable entity type name
     * @param businessKey the business key that violated the constraint
     * @param action      the repository save or insert to execute
     * @param <T>         return type of the action
     * @return the action's return value
     * @throws ConcurrencyConflictException if an OCC or unique constraint conflict is detected
     */
    public static <T> T withUniqueConstraintGuard(String entityType, String businessKey,
                                                   Supplier<T> action) {
        try {
            return action.get();
        } catch (ObjectOptimisticLockingFailureException ex) {
            logConflict(entityType, businessKey, "OPTIMISTIC_LOCK", ex.getMessage());
            throw ConcurrencyConflictException.optimisticLock(entityType, businessKey);
        } catch (DataIntegrityViolationException ex) {
            logConflict(entityType, businessKey, "DUPLICATE_CREATE",
                    ex.getMostSpecificCause().getMessage());
            throw ConcurrencyConflictException.duplicateCreate(entityType, businessKey);
        }
    }

    // ── Structured conflict logging ───────────────────────────────────────────

    /**
     * Logs a concurrency conflict with MDC context included.
     * Call this at the point of detection before translating or re-throwing.
     *
     * @param entityType   entity class name
     * @param entityId     primary key or business key
     * @param conflictType description of the conflict type
     * @param detail       exception message or additional detail
     */
    public static void logConflict(String entityType, Object entityId,
                                    String conflictType, String detail) {
        log.warn(
                "Concurrency conflict detected: type={} entityType={} entityId={} "
                        + "requestId={} correlationId={} merchantId={} detail={}",
                conflictType,
                entityType,
                entityId,
                MDC.get("requestId"),
                MDC.get("correlationId"),
                MDC.get("merchantId"),
                detail
        );
    }
}
