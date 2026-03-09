package com.firstclub.platform.lock;

/**
 * Contract for acquiring and releasing PostgreSQL advisory locks.
 *
 * <h3>What are advisory locks?</h3>
 * Advisory locks are application-defined mutex locks managed by PostgreSQL.
 * They do not tie to a specific table row — the application is responsible for
 * choosing a meaningful lock identifier ({@code lockId}).  Unlike row-level
 * locks, advisory locks survive explicit rollbacks and must be released
 * explicitly (or they are released automatically when the DB session ends).
 *
 * <h3>When to use this vs. DistributedLockService</h3>
 * <ul>
 *   <li>Use {@link DistributedLockService} for cross-service / cross-datacenter
 *       coordination — it uses Redis which is visible to all application nodes.</li>
 *   <li>Use this interface for <em>intra-database</em> coordination, e.g.
 *       ensuring a single scheduler node runs a maintenance job within the
 *       same PostgreSQL connection pool ({@link com.firstclub.platform.concurrency.locking.LockingStrategy#ADVISORY}).</li>
 * </ul>
 *
 * <h3>PostgreSQL native queries</h3>
 * Implementations use {@code pg_advisory_lock}, {@code pg_try_advisory_lock},
 * and {@code pg_advisory_unlock} via
 * {@code @Query(value="SELECT pg_advisory_lock(?1)", nativeQuery=true)}.
 * These functions are <strong>not available in H2</strong> — implementations
 * must be guarded accordingly in non-production profiles.
 *
 * <h3>Lock ID derivation</h3>
 * Use the {@link #toLockId} helper to derive a stable {@code long} from a
 * domain string such as {@code "scheduler:dunning_daily"}.
 */
public interface AdvisoryLockRepository {

    /**
     * Acquires a session-level advisory lock, blocking until it is available.
     * The lock is automatically released when the underlying DB session ends.
     *
     * <p>Must be called within a Spring-managed transaction or a live DB session.
     *
     * @param lockId a stable integer identifier for the logical lock
     */
    void acquireAdvisoryLock(long lockId);

    /**
     * Attempts to acquire a session-level advisory lock without blocking.
     *
     * @param lockId a stable integer identifier for the logical lock
     * @return {@code true} if the lock was acquired, {@code false} if already held
     */
    boolean tryAdvisoryLock(long lockId);

    /**
     * Releases a previously acquired session-level advisory lock.
     *
     * @param lockId the same identifier passed to {@link #acquireAdvisoryLock}
     */
    void releaseAdvisoryLock(long lockId);

    /**
     * Attempts to acquire a <em>transaction-scoped</em> advisory lock without
     * blocking.
     *
     * <p>Unlike {@link #tryAdvisoryLock}, the lock is automatically released
     * when the enclosing transaction commits or rolls back — no explicit
     * {@link #releaseAdvisoryLock} call is needed or possible.  This is the
     * preferred variant for batch-processor mutual exclusion because the lock
     * lifetime is exactly tied to the unit of work.
     *
     * <p>Must be called within an active Spring-managed transaction.
     *
     * @param lockId a stable integer identifier for the logical lock
     * @return {@code true} if the lock was acquired; {@code false} if another
     *         transaction holds it
     */
    boolean tryAdvisoryXactLock(long lockId);

    /**
     * Derives a stable {@code long} lock ID from an arbitrary namespace + key.
     *
     * <p>Implementation: {@code Math.abs(Objects.hash(namespace, key))} cast to
     * {@code int} then widened to {@code long}, giving a stable 31-bit identifier.
     * The collision probability is acceptable for the O(100) distinct scheduler
     * jobs that use advisory locks in this platform.
     *
     * @param namespace domain segment, e.g. {@code "scheduler"}
     * @param key       operation name, e.g. {@code "dunning_daily"}
     * @return a stable non-negative lock ID
     */
    static long toLockId(String namespace, String key) {
        return Math.abs((long) java.util.Objects.hash(namespace, key));
    }
}
