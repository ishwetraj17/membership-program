package com.firstclub.platform.concurrency.locking;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Thin abstraction over JPA pessimistic row-locking ({@code SELECT ... FOR UPDATE}).
 *
 * <p>All methods must be invoked within an active Spring transaction; the lock is
 * held until the surrounding transaction commits or rolls back.
 *
 * <h3>Usage example</h3>
 * <pre>{@code
 *   PaymentIntentV2 locked = pessimisticLockHelper
 *       .lockAndGet(em, PaymentIntentV2.class, paymentIntentId)
 *       .orElseThrow(() -> new EntityNotFoundException("PaymentIntent " + paymentIntentId));
 * }</pre>
 *
 * @see LockingDecisionCatalog — entries that recommend {@link LockingStrategy#PESSIMISTIC_FOR_UPDATE}
 */
@Component
public class PessimisticLockHelper {

    /**
     * Loads an entity and immediately acquires a {@code PESSIMISTIC_WRITE} lock on it.
     *
     * <p>Must be called within a transaction.
     *
     * @param em          the {@link EntityManager} whose current transaction will hold the lock
     * @param entityClass JPA entity class
     * @param id          primary key value
     * @param <T>         entity type
     * @param <ID>        primary key type
     * @return the locked entity, or empty if no entity with that id exists
     */
    public <T, ID> Optional<T> lockAndGet(EntityManager em, Class<T> entityClass, ID id) {
        T entity = em.find(entityClass, id, LockModeType.PESSIMISTIC_WRITE);
        return Optional.ofNullable(entity);
    }

    /**
     * Upgrades an already-loaded managed entity to a {@code PESSIMISTIC_WRITE} lock.
     *
     * <p>Use this when the entity is already in the persistence context but stronger
     * isolation is required before making a critical decision based on its state.
     *
     * @param em     the {@link EntityManager} managing the entity
     * @param entity the managed entity instance to lock
     */
    public void lock(EntityManager em, Object entity) {
        em.lock(entity, LockModeType.PESSIMISTIC_WRITE);
    }
}
