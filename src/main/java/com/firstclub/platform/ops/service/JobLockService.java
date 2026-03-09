package com.firstclub.platform.ops.service;

import com.firstclub.platform.ops.entity.JobLock;

import java.time.LocalDateTime;
import java.util.List;

public interface JobLockService {

    /**
     * Attempts to acquire the named lock.
     *
     * <p>If no row exists yet for {@code jobName}, a new row is inserted.
     * If a row exists and is free (lock expired or was released), it is
     * updated atomically.
     *
     * @return {@code true} if this caller now holds the lock;
     *         {@code false} if the lock is currently held by another owner.
     */
    boolean acquireLock(String jobName, String lockedBy, LocalDateTime until);

    /**
     * Releases the lock only when the caller is the current owner.
     * A no-op (with a warning log) if the caller does not own the lock.
     */
    void releaseLock(String jobName, String lockedBy);

    List<JobLock> listLocks();
}
