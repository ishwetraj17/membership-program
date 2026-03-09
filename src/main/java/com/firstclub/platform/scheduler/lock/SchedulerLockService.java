package com.firstclub.platform.scheduler.lock;

import com.firstclub.platform.lock.AdvisoryLockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service facade for acquiring PostgreSQL advisory locks scoped to scheduler jobs.
 *
 * <h3>Design</h3>
 * Schedulers should not call {@link AdvisoryLockRepository} directly because:
 * <ol>
 *   <li>Lock ID derivation from a scheduler name is non-trivial (namespace
 *       hashing) and must be consistent across nodes.</li>
 *   <li>Log/metrics instrumentation must be centralised so that lock-skipped
 *       runs are observable.</li>
 *   <li>The service can be mocked in unit tests without requiring a live DB.</li>
 * </ol>
 *
 * <h3>Transaction-scoped vs. session-scoped advisory locks</h3>
 * {@link #tryAcquireForBatch} uses {@code pg_try_advisory_xact_lock} (transaction-
 * scoped).  The lock is released automatically on transaction commit or rollback.
 * Callers must invoke this inside a {@link org.springframework.transaction.annotation.Transactional}
 * method — the scheduler method itself or a wrapping service boundary.
 *
 * For long-running, multi-transaction jobs, use {@link #tryAcquireSessionLock}
 * instead and release explicitly with {@link #releaseSessionLock}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchedulerLockService {

    static final String SCHEDULER_NAMESPACE = "scheduler";

    private final JdbcAdvisoryLockRepository advisoryLockRepo;

    /**
     * Attempts to acquire a transaction-scoped advisory lock for {@code schedulerName}.
     *
     * <p>If another node is already holding the lock (i.e. it is inside its own
     * transaction), this method returns {@code false} immediately without blocking.
     * The calling scheduler should skip its execution cycle and log the skip.
     *
     * <p><strong>Must be called inside an active transaction.</strong>  The lock is
     * released automatically when the transaction commits or rolls back.
     *
     * @param schedulerName logical name of the scheduler
     * @return {@code true} if the lock was acquired and the caller should proceed;
     *         {@code false} if another instance holds the lock
     */
    public boolean tryAcquireForBatch(String schedulerName) {
        long lockId = lockIdFor(schedulerName);
        boolean acquired = advisoryLockRepo.tryAdvisoryXactLock(lockId);
        if (acquired) {
            log.info("[SCHEDULER-LOCK] Acquired batch lock scheduler={} lockId={}",
                    schedulerName, lockId);
        } else {
            log.info("[SCHEDULER-LOCK] Lock busy — skipping scheduler={} lockId={}",
                    schedulerName, lockId);
        }
        return acquired;
    }

    /**
     * Attempts to acquire a session-level advisory lock for {@code schedulerName}.
     *
     * <p>Session locks survive transaction boundaries and must be released
     * explicitly via {@link #releaseSessionLock}.  Use for schedulers that span
     * multiple transactions (e.g. large reconciliation batches processed in
     * paginated chunks).
     *
     * @param schedulerName logical name of the scheduler
     * @return {@code true} if the lock was acquired; {@code false} if busy
     */
    public boolean tryAcquireSessionLock(String schedulerName) {
        long lockId = lockIdFor(schedulerName);
        boolean acquired = advisoryLockRepo.tryAdvisoryLock(lockId);
        if (acquired) {
            log.info("[SCHEDULER-LOCK] Acquired session lock scheduler={} lockId={}",
                    schedulerName, lockId);
        } else {
            log.info("[SCHEDULER-LOCK] Session lock busy — skipping scheduler={} lockId={}",
                    schedulerName, lockId);
        }
        return acquired;
    }

    /**
     * Releases a session-level advisory lock previously acquired via
     * {@link #tryAcquireSessionLock}.
     *
     * @param schedulerName logical name of the scheduler
     */
    public void releaseSessionLock(String schedulerName) {
        long lockId = lockIdFor(schedulerName);
        advisoryLockRepo.releaseAdvisoryLock(lockId);
        log.info("[SCHEDULER-LOCK] Released session lock scheduler={} lockId={}",
                schedulerName, lockId);
    }

    /**
     * Returns the stable advisory lock ID for a given scheduler name.
     *
     * <p>Exposed for testing and diagnostic endpoints.
     *
     * @param schedulerName logical scheduler name
     * @return deterministic non-negative lock ID
     */
    public long lockIdFor(String schedulerName) {
        return AdvisoryLockRepository.toLockId(SCHEDULER_NAMESPACE, schedulerName);
    }
}
