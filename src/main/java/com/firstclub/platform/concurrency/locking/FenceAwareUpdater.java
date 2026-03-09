package com.firstclub.platform.concurrency.locking;

/**
 * Interface for entities or services that participate in the <em>fencing token</em>
 * pattern for distributed-lock correctness.
 *
 * <h3>Motivation</h3>
 * A process that holds a distributed lock may be paused (GC pause, network partition)
 * and its lock may expire while it is still executing.  A fence token — a
 * monotonically increasing counter issued by the lock server — lets the storage
 * layer reject writes from a process whose token is stale, even if that process
 * believes it still owns the lock.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   long fence = distributedLock.acquireAndGetFenceToken();
 *   T loaded = repo.findById(id).orElseThrow();
 *   if (fenceAwareUpdater.isStillValid(loaded, fence)) {
 *       T result = fenceAwareUpdater.update(loaded, fence);
 *   }
 * }</pre>
 *
 * @param <T> the entity or aggregate type being updated
 */
public interface FenceAwareUpdater<T> {

    /**
     * Applies the domain update to {@code entity}, recording {@code fenceToken}
     * so the storage layer can reject replayed or late-arriving writes.
     *
     * @param entity     the entity loaded before the update
     * @param fenceToken monotonically increasing token issued by the distributed lock server
     * @return the updated entity (may be the same instance or a new one)
     */
    T update(T entity, long fenceToken);

    /**
     * Returns {@code true} if this update is still valid, i.e. the entity's stored
     * fence token has not already been superseded by a higher token.
     *
     * @param entity     the entity as currently stored in the database
     * @param fenceToken the token held by the current lock owner
     * @return {@code true} if the update should proceed; {@code false} if it should abort
     */
    boolean isStillValid(T entity, long fenceToken);
}
