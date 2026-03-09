package com.firstclub.platform.lock;

import java.util.Optional;

/**
 * Result of a single {@link DistributedLockService#tryAcquire} attempt.
 *
 * <p>The status distinguishes between three outcomes so callers can choose
 * the appropriate remediation:
 * <ul>
 *   <li>{@link Status#ACQUIRED} — lock acquired; {@link #lockHandle} is present</li>
 *   <li>{@link Status#FAILED_ALREADY_LOCKED} — another process holds the lock;
 *       caller may retry with back-off</li>
 *   <li>{@link Status#FAILED_REDIS_UNAVAILABLE} — Redis is down or misconfigured;
 *       caller must decide whether to degrade gracefully or abort</li>
 * </ul>
 */
public final class LockAcquisitionResult {

    public enum Status {
        ACQUIRED,
        FAILED_ALREADY_LOCKED,
        FAILED_REDIS_UNAVAILABLE
    }

    private final Status status;
    private final LockHandle handle; // non-null only when status == ACQUIRED

    private LockAcquisitionResult(Status status, LockHandle handle) {
        this.status = status;
        this.handle = handle;
    }

    /** Factory: lock was successfully acquired. */
    public static LockAcquisitionResult acquired(LockHandle handle) {
        return new LockAcquisitionResult(Status.ACQUIRED, handle);
    }

    /** Factory: lock is currently held by another process. */
    public static LockAcquisitionResult alreadyLocked() {
        return new LockAcquisitionResult(Status.FAILED_ALREADY_LOCKED, null);
    }

    /** Factory: Redis was not available; could not attempt acquisition. */
    public static LockAcquisitionResult redisUnavailable() {
        return new LockAcquisitionResult(Status.FAILED_REDIS_UNAVAILABLE, null);
    }

    /** Returns {@code true} when the lock was successfully acquired. */
    public boolean isAcquired() {
        return status == Status.ACQUIRED;
    }

    public Status getStatus() {
        return status;
    }

    /**
     * Returns the {@link LockHandle} when the lock was acquired, or empty otherwise.
     * Always call {@link #isAcquired()} before unwrapping.
     */
    public Optional<LockHandle> lockHandle() {
        return Optional.ofNullable(handle);
    }
}
