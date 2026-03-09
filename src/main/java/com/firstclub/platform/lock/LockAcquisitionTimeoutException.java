package com.firstclub.platform.lock;

import java.time.Duration;

/**
 * Thrown when {@link DistributedLockService#acquireWithRetry} exhausts its
 * retry window without acquiring the requested lock.
 *
 * <p>Maps to HTTP 503 (Service Unavailable) or 409 (Conflict) depending on
 * the caller's semantics.  The caller should propagate this to the client
 * with a {@code Retry-After} header where appropriate.
 */
public class LockAcquisitionTimeoutException extends RuntimeException {

    private final String resourceType;
    private final String resourceId;
    private final int attempts;
    private final Duration timeout;

    public LockAcquisitionTimeoutException(String resourceType,
                                            String resourceId,
                                            int attempts,
                                            Duration timeout) {
        super(String.format(
                "[LOCK-TIMEOUT] Failed to acquire distributed lock on %s/%s "
                        + "after %d attempt(s) within %s timeout",
                resourceType, resourceId, attempts, timeout));
        this.resourceType = resourceType;
        this.resourceId   = resourceId;
        this.attempts     = attempts;
        this.timeout      = timeout;
    }

    public String getResourceType() { return resourceType; }
    public String getResourceId()   { return resourceId; }
    public int    getAttempts()     { return attempts; }
    public Duration getTimeout()    { return timeout; }
}
