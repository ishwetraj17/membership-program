package com.firstclub.platform.scheduler.lock;

import com.firstclub.platform.lock.AdvisoryLockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of {@link AdvisoryLockRepository} backed by PostgreSQL
 * advisory lock functions.
 *
 * <h3>Session-level vs. transaction-level locks</h3>
 * <ul>
 *   <li>{@link #tryAdvisoryLock}/{@link #acquireAdvisoryLock}/{@link #releaseAdvisoryLock}
 *       use <em>session-level</em> functions ({@code pg_advisory_lock},
 *       {@code pg_try_advisory_lock}, {@code pg_advisory_unlock}).  These survive
 *       ROLLBACK and must be released explicitly or depend on session teardown.</li>
 *   <li>{@link #tryAdvisoryXactLock} uses the <em>transaction-scoped</em>
 *       {@code pg_try_advisory_xact_lock}.  The lock is released automatically
 *       when the enclosing transaction commits or rolls back — this is the
 *       preferred variant for batch-processor scheduler mutual exclusion.</li>
 * </ul>
 *
 * <h3>PostgreSQL availability</h3>
 * Advisory lock functions are PostgreSQL-specific and are NOT available in H2.
 * Unit tests should mock this class; integration tests require a live PG instance
 * (e.g. via Testcontainers).
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class JdbcAdvisoryLockRepository implements AdvisoryLockRepository {

    private final JdbcTemplate jdbc;

    /**
     * Acquires a session-level advisory lock for {@code lockId}, blocking
     * indefinitely.
     */
    @Override
    public void acquireAdvisoryLock(long lockId) {
        log.debug("[ADVISORY-LOCK] Blocking acquire lockId={}", lockId);
        jdbc.queryForObject("SELECT pg_advisory_lock(?)", Void.class, lockId);
    }

    /**
     * Attempts a non-blocking session-level advisory lock acquisition.
     *
     * @return {@code true} if the lock was acquired immediately
     */
    @Override
    public boolean tryAdvisoryLock(long lockId) {
        Boolean acquired = jdbc.queryForObject(
                "SELECT pg_try_advisory_lock(?)", Boolean.class, lockId);
        boolean ok = Boolean.TRUE.equals(acquired);
        log.debug("[ADVISORY-LOCK] tryAdvisoryLock lockId={} acquired={}", lockId, ok);
        return ok;
    }

    /**
     * Releases a session-level advisory lock.
     */
    @Override
    public void releaseAdvisoryLock(long lockId) {
        log.debug("[ADVISORY-LOCK] Release session lock lockId={}", lockId);
        jdbc.queryForObject("SELECT pg_advisory_unlock(?)", Void.class, lockId);
    }

    /**
     * Attempts a non-blocking <em>transaction-scoped</em> advisory lock.
     *
     * <p>The lock is automatically released on transaction commit or rollback.
     * Must be called inside an active Spring-managed transaction; calling outside
     * a transaction will throw {@link org.springframework.dao.DataAccessException}.
     *
     * <p>This is the preferred method for scheduler singleton mutual exclusion
     * because the lock lifetime is exactly bounded to the batch transaction.
     *
     * @return {@code true} if the lock was acquired; {@code false} if another
     *         transaction holds it
     */
    @Override
    public boolean tryAdvisoryXactLock(long lockId) {
        Boolean acquired = jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, lockId);
        boolean ok = Boolean.TRUE.equals(acquired);
        log.debug("[ADVISORY-LOCK] tryAdvisoryXactLock lockId={} acquired={}", lockId, ok);
        return ok;
    }
}
