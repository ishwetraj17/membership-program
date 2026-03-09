package com.firstclub.platform.concurrency.locking;

/**
 * Taxonomy of locking strategies used in this platform.
 *
 * <p>Every strategy is documented with its guarantees, implementation mechanism,
 * and the failure mode a caller should expect.  The authoritative mapping from
 * domain operations to strategies lives in {@link LockingDecisionCatalog}.
 */
public enum LockingStrategy {

    /**
     * JPA {@code @Version} field — Hibernate detects version mismatch at commit.
     * Conflict surfaces as {@link com.firstclub.platform.concurrency.ConcurrencyConflictException}.
     * Caller should retry with a freshly-loaded entity.
     */
    OPTIMISTIC,

    /**
     * Distributed lock + fence token.  Ensures only one node proceeds; a higher
     * fence token invalidates any ongoing work from a previous lock holder.
     * Not yet fully implemented — placeholder for future distributed-lock integration.
     */
    DISTRIBUTED_FENCE,

    /**
     * Database advisory lock or ShedLock-style row reservation.  Suitable for
     * preventing duplicate scheduler execution across multiple application instances.
     */
    ADVISORY,

    /**
     * {@code SELECT ... FOR UPDATE} — pessimistic row lock held for the duration
     * of the transaction.  Prevents concurrent read-modify-write on the same row.
     */
    PESSIMISTIC_FOR_UPDATE,

    /**
     * {@code SELECT ... FOR UPDATE SKIP LOCKED} — processes only rows not already
     * locked by another transaction.  Ideal for work-queue polling (outbox, retry queues).
     */
    SKIP_LOCKED,

    /**
     * SERIALIZABLE transaction isolation — the database guarantees no phantom reads
     * or write skew.  Higher contention cost; use only where data integrity requires it.
     */
    SERIALIZABLE,

    /**
     * Idempotent asynchronous update — no strong lock needed because the operation is
     * designed to be safely re-applied (e.g. projection rebuild, aggregate snapshot).
     */
    IDEMPOTENT_ASYNC
}
