package com.firstclub.platform.concurrency;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a concurrent write operation conflicts with an in-progress or
 * already-committed write on the same entity.
 *
 * <p>Maps to HTTP 409 Conflict in {@code GlobalExceptionHandler}.
 *
 * <p>Carries structured metadata so conflict log entries are always
 * queryable by entity type, entity ID, conflict reason, and request context.
 *
 * <p>Use this exception instead of letting Hibernate's
 * {@code ObjectOptimisticLockingFailureException} surface as a raw 500.
 */
@Getter
public class ConcurrencyConflictException extends RuntimeException {

    private final String entityType;
    private final String entityId;
    private final ConflictReason reason;
    private final String errorCode;

    public ConcurrencyConflictException(String entityType, String entityId,
                                         ConflictReason reason, String detail) {
        super(String.format("[%s] Concurrency conflict on %s id=%s: %s",
                reason, entityType, entityId, detail));
        this.entityType = entityType;
        this.entityId   = entityId;
        this.reason     = reason;
        this.errorCode  = "CONCURRENCY_CONFLICT_" + reason.name();
    }

    public HttpStatus httpStatus() {
        return HttpStatus.CONFLICT;
    }

    // ── Factory helpers ───────────────────────────────────────────────────────

    /** Optimistic lock lost — another transaction committed on the same version. */
    public static ConcurrencyConflictException optimisticLock(String entityType, Object entityId) {
        return new ConcurrencyConflictException(entityType, String.valueOf(entityId),
                ConflictReason.OPTIMISTIC_LOCK,
                "Version mismatch — another concurrent write committed first. Retry with fresh state.");
    }

    /** Entity is already in a terminal state; the requested transition is illegal. */
    public static ConcurrencyConflictException terminalState(String entityType, Object entityId,
                                                              String currentState) {
        return new ConcurrencyConflictException(entityType, String.valueOf(entityId),
                ConflictReason.TERMINAL_STATE,
                "Entity is already in terminal state '" + currentState
                        + "'. This transition is irreversible.");
    }

    /**
     * A concurrent create produced a duplicate business key (unique constraint violation
     * that is not a DB constraint violation we want to expose raw).
     */
    public static ConcurrencyConflictException duplicateCreate(String entityType, String businessKey) {
        return new ConcurrencyConflictException(entityType, businessKey,
                ConflictReason.DUPLICATE_CREATE,
                "An entity with key '" + businessKey + "' already exists (concurrent create race).");
    }

    // ── Conflict type enumeration ─────────────────────────────────────────────

    public enum ConflictReason {
        /** JPA @Version mismatch — stale entity was updated. */
        OPTIMISTIC_LOCK,
        /** Transition attempted on a terminal state entity. */
        TERMINAL_STATE,
        /** Two concurrent creates raced for the same business key. */
        DUPLICATE_CREATE,
        /** Attempt number or sequence could not be safely incremented. */
        SEQUENCE_RACE,
        /** Processing lock already held by another worker. */
        PROCESSING_LOCKED
    }
}
