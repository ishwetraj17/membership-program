package com.firstclub.platform.concurrency.locking;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * Executes work inside a {@code SERIALIZABLE} isolation transaction.
 *
 * <p>Serializable isolation prevents phantom reads and write skew but incurs the
 * highest contention cost of any isolation level.  Use only for operations where
 * correctness is impossible to achieve with lower isolation (e.g. a balance check
 * that must account for all concurrent writes on the same set of rows).
 *
 * <h3>Spring AOP requirement</h3>
 * {@link #executeSerializable} is annotated with {@code @Transactional}.  For those
 * semantics to take effect, callers must invoke this method on the injected Spring
 * bean, not on a directly-constructed instance.
 */
@Component
public class SerializableTxnExecutor {

    /**
     * Executes {@code work} inside a {@code SERIALIZABLE} transaction.
     *
     * <p>If a transaction is already active, Spring's default {@code REQUIRED}
     * propagation will join it. Wrap in {@code REQUIRES_NEW} at the call site if
     * strict isolation on a fresh transaction is needed.
     *
     * @param work the database read/write logic to execute
     * @param <T>  return type
     * @return the result of {@code work.get()}
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public <T> T executeSerializable(Supplier<T> work) {
        return work.get();
    }
}
