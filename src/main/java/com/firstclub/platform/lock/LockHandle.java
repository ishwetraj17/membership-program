package com.firstclub.platform.lock;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A handle to an acquired distributed lock.
 *
 * <p>Implements {@link AutoCloseable} so it can be used in a
 * {@code try-with-resources} block, which guarantees the lock is released
 * even if an exception is thrown:
 *
 * <pre>{@code
 *   try (LockHandle lock = lockService.acquireWithRetry("subscription", id, ttl, timeout)) {
 *       // critical section — only this thread/node holds the lock
 *       long fence = lock.getFenceToken();
 *       doWork();
 *       repo.updateWithFenceCheck(id, fence, newState);  // safe DB write
 *   }
 * }</pre>
 *
 * <h3>Fence token usage</h3>
 * The fence token is a monotonically increasing counter obtained from Redis
 * INCR immediately after the lock is acquired.  Pass it to every downstream
 * DB write so the write can be rejected if a higher token has already been
 * committed by a newer lock holder.
 *
 * <h3>Thread safety</h3>
 * {@link #close()} is idempotent — calling it multiple times has no additional
 * effect after the first invocation.
 */
public final class LockHandle implements AutoCloseable {

    private final String resourceType;
    private final String resourceId;
    private final String lockKey;   // full Redis key, e.g. "prod:firstclub:lock:subscription:42"
    private final String lockOwner; // {instanceId}:{threadId}:{uuid}
    private final long fenceToken;
    private final Instant acquiredAt;
    private final AtomicBoolean released;
    private final Runnable onClose;

    public LockHandle(String resourceType,
                      String resourceId,
                      String lockKey,
                      String lockOwner,
                      long fenceToken,
                      Instant acquiredAt,
                      Runnable onClose) {
        this.resourceType = resourceType;
        this.resourceId   = resourceId;
        this.lockKey      = lockKey;
        this.lockOwner    = lockOwner;
        this.fenceToken   = fenceToken;
        this.acquiredAt   = acquiredAt;
        this.released     = new AtomicBoolean(false);
        this.onClose      = onClose;
    }

    /**
     * Releases the lock.  Safe to call multiple times — only the first call
     * executes the release; subsequent calls are no-ops.
     */
    @Override
    public void close() {
        if (released.compareAndSet(false, true)) {
            onClose.run();
        }
    }

    /** Returns {@code true} if this handle has been closed / released. */
    public boolean isReleased() {
        return released.get();
    }

    public String getResourceType() { return resourceType; }
    public String getResourceId()   { return resourceId; }
    public String getLockKey()      { return lockKey; }
    public String getLockOwner()    { return lockOwner; }
    public long   getFenceToken()   { return fenceToken; }
    public Instant getAcquiredAt()  { return acquiredAt; }
}
