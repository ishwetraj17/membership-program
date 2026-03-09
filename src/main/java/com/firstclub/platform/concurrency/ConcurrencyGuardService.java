package com.firstclub.platform.concurrency;

import com.firstclub.platform.concurrency.locking.LockingDecision;
import com.firstclub.platform.concurrency.locking.LockingDecisionCatalog;
import com.firstclub.platform.concurrency.locking.SerializableTxnExecutor;
import com.firstclub.platform.concurrency.retry.OptimisticRetryTemplate;
import com.firstclub.platform.concurrency.retry.RetryBackoffPolicy;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Central facade for applying concurrency controls to domain operations.
 *
 * <p>This service composes the retry, serializable-transaction, and lock-decision
 * primitives into a single injection point so that domain services do not depend
 * directly on the individual low-level helpers.
 *
 * <h3>Design constraints</h3>
 * <ul>
 *   <li>{@link #withPessimisticLock} <em>documents intent only</em> — it does not
 *       acquire a lock.  Callers must use a repository-level
 *       {@code @Lock(PESSIMISTIC_WRITE)} or
 *       {@link com.firstclub.platform.concurrency.locking.PessimisticLockHelper}
 *       to issue the actual {@code SELECT ... FOR UPDATE}.</li>
 *   <li>Distributed locks are <strong>not</strong> implemented in this phase.</li>
 * </ul>
 */
@Service
public class ConcurrencyGuardService {

    private final OptimisticRetryTemplate retryTemplate;
    private final SerializableTxnExecutor serializableTxnExecutor;
    private final LockingDecisionCatalog lockingDecisionCatalog;

    public ConcurrencyGuardService(OptimisticRetryTemplate retryTemplate,
                                    SerializableTxnExecutor serializableTxnExecutor,
                                    LockingDecisionCatalog lockingDecisionCatalog) {
        this.retryTemplate = retryTemplate;
        this.serializableTxnExecutor = serializableTxnExecutor;
        this.lockingDecisionCatalog = lockingDecisionCatalog;
    }

    /**
     * Executes {@code operation} with OCC retry using the default back-off policy.
     *
     * @param operation must re-fetch entity on every invocation — never capture a stale entity
     * @param context   human-readable label for log messages (e.g. {@code "SubscriptionRenewal#42"})
     * @param <T>       return type
     * @return result of the first successful attempt
     * @throws ConcurrencyConflictException after all retry attempts are exhausted
     */
    public <T> T withOptimisticRetry(Supplier<T> operation, String context) {
        return retryTemplate.executeWithRetry(operation, context);
    }

    /**
     * Executes {@code operation} with OCC retry using a custom policy.
     *
     * @param operation must re-fetch entity on every invocation
     * @param context   human-readable label for log messages
     * @param policy    controls max retries, base delay, multiplier, and jitter
     * @param <T>       return type
     * @return result of the first successful attempt
     * @throws ConcurrencyConflictException after all retry attempts are exhausted
     */
    public <T> T withOptimisticRetry(Supplier<T> operation, String context,
                                      RetryBackoffPolicy policy) {
        return retryTemplate.executeWithRetry(operation, context, policy);
    }

    /**
     * Executes {@code work} inside a {@code SERIALIZABLE} transaction.
     *
     * <p>Use for operations that must prevent phantom reads or write skew.
     *
     * @param work the logic to execute under serializable isolation
     * @param <T>  return type
     * @return result of {@code work.get()}
     */
    public <T> T withSerializable(Supplier<T> work) {
        return serializableTxnExecutor.executeSerializable(work);
    }

    /**
     * Executes {@code work}, documenting the caller's intent to use pessimistic
     * ({@code SELECT FOR UPDATE}) locking.
     *
     * <p>This method <em>does not acquire any lock</em> — it delegates directly to
     * {@code work.get()}.  To actually lock a row, use
     * {@link com.firstclub.platform.concurrency.locking.PessimisticLockHelper}
     * or a Spring Data {@code @Lock(LockModeType.PESSIMISTIC_WRITE)} repository method
     * within the supplier.
     *
     * @param work the logic that uses repository-level pessimistic locking
     * @param <T>  return type
     * @return result of {@code work.get()}
     */
    public <T> T withPessimisticLock(Supplier<T> work) {
        // Lock acquisition is the repository's responsibility (SELECT FOR UPDATE).
        // This method exists as a named, self-documenting call-site wrapper.
        return work.get();
    }

    /**
     * Returns the recommended locking strategy for the named domain operation.
     *
     * @param domainOperation e.g. {@code "subscription.renewal"}, {@code "refund.create"}
     * @return the catalogued decision, or empty if not yet catalogued
     */
    public Optional<LockingDecision> getLockingDecision(String domainOperation) {
        return lockingDecisionCatalog.forOperation(domainOperation);
    }
}
