package com.firstclub.platform.concurrency.retry;

import com.firstclub.platform.concurrency.ConcurrencyConflictException;
import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Retries a JPA write operation on optimistic-locking conflicts using exponential
 * back-off with jitter.
 *
 * <h3>Entity-reload contract</h3>
 * When an optimistic-lock conflict occurs, the root cause is a stale in-memory entity
 * version.  <strong>This template does not reload the entity.</strong>  The caller's
 * {@link Supplier} is invoked fresh on every attempt; therefore the supplier must
 * re-fetch the entity from the database rather than capturing a stale instance in a
 * closure.  A supplier that captures a stale entity will conflict on every attempt.
 *
 * <h3>Caught exception types</h3>
 * <ul>
 *   <li>{@link jakarta.persistence.OptimisticLockException}</li>
 *   <li>{@link org.springframework.orm.ObjectOptimisticLockingFailureException}
 *       (subtype of {@link org.springframework.dao.OptimisticLockingFailureException})</li>
 *   <li>{@link org.springframework.dao.OptimisticLockingFailureException}</li>
 * </ul>
 */
@Slf4j
@Component
public class OptimisticRetryTemplate {

    private final RetryJitterStrategy jitterStrategy;

    public OptimisticRetryTemplate(RetryJitterStrategy jitterStrategy) {
        this.jitterStrategy = jitterStrategy;
    }

    /**
     * Executes {@code operation} using {@link RetryBackoffPolicy#defaultPolicy()}.
     *
     * @param operation   must re-fetch entity on every invocation
     * @param lockContext human-readable label used in log messages (e.g. {@code "Subscription#42"})
     * @param <T>         return type
     * @return the value returned by the first successful invocation
     * @throws ConcurrencyConflictException when all retry attempts are exhausted
     */
    public <T> T executeWithRetry(Supplier<T> operation, String lockContext) {
        return executeWithRetry(operation, lockContext, RetryBackoffPolicy.defaultPolicy());
    }

    /**
     * Executes {@code operation} using the given {@code policy}.
     *
     * @param operation   must re-fetch entity on every invocation
     * @param lockContext human-readable label used in log messages
     * @param policy      controls max retries, base delay, multiplier, and jitter fraction
     * @param <T>         return type
     * @return the value returned by the first successful invocation
     * @throws ConcurrencyConflictException when all retry attempts are exhausted
     */
    public <T> T executeWithRetry(Supplier<T> operation, String lockContext,
                                   RetryBackoffPolicy policy) {
        int attempt = 0;
        while (true) {
            try {
                return operation.get();
            } catch (OptimisticLockException | OptimisticLockingFailureException ex) {
                if (attempt >= policy.maxRetries()) {
                    log.warn("[OCC-EXHAUSTED] context={} totalAttempts={}", lockContext, attempt + 1);
                    throw ConcurrencyConflictException.optimisticLock(
                            lockContext, "after-" + (attempt + 1) + "-attempts");
                }
                long delayMs = policy.computeDelay(attempt, jitterStrategy);
                log.debug("[OCC-RETRY] context={} attempt={}/{} backoffMs={}",
                        lockContext, attempt + 1, policy.maxRetries(), delayMs);
                sleepQuietly(delayMs);
                attempt++;
            }
        }
    }

    private void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ConcurrencyConflictException(
                    "OptimisticRetry", "sleep-interrupted",
                    ConcurrencyConflictException.ConflictReason.OPTIMISTIC_LOCK,
                    "Retry sleep was interrupted");
        }
    }
}
