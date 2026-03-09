package com.firstclub.platform.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Periodically extends a distributed lock's TTL while the owning thread is
 * still working, preventing the lock from expiring before the work completes.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   try (LockHandle lock = lockService.acquireWithRetry("subscription", id, ttl, timeout)) {
 *       heartbeat.register(lock, Duration.ofSeconds(10), ttl);   // extend every 10 s
 *       doExpensiveWork();
 *   }
 *   // LockHandle.close() → releases lock; heartbeat stops automatically on next tick
 * }</pre>
 *
 * <h3>Self-cancellation</h3>
 * Each heartbeat tick checks {@link LockHandle#isReleased()}.  If the handle
 * was already released (by the try block closing or by an explicit
 * {@link DistributedLockService#release}), the heartbeat cancels itself so no
 * extension attempt is made on a dead lock.
 *
 * <h3>Extension failure</h3>
 * If {@link DistributedLockService#extend} returns {@code false} (the lock
 * expired or was stolen), the heartbeat logs a warning and cancels itself.
 * The calling code will discover the loss of the lock on its next interaction
 * with the shared resource (e.g., a fence-token check in the DB write will
 * reject the stale token).
 */
@Slf4j
@Component
public class LockLeaseHeartbeat {

    private final DistributedLockService lockService;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> activeTasks;

    public LockLeaseHeartbeat(DistributedLockService lockService) {
        this.lockService  = lockService;
        this.activeTasks  = new ConcurrentHashMap<>();
        this.scheduler    = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lock-lease-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts a periodic heartbeat for the given lock.
     *
     * @param handle          the active lock handle to extend
     * @param renewalInterval how often to attempt an extension
     * @param extendedTtl     the new TTL applied on each extension
     */
    public void register(LockHandle handle, Duration renewalInterval, Duration extendedTtl) {
        String taskKey = heartbeatKey(handle);
        // Cancel any previous heartbeat for this resource+owner (shouldn't happen in normal use)
        cancelByKey(taskKey);

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
                () -> heartbeatTick(handle, extendedTtl, taskKey),
                renewalInterval.toMillis(),
                renewalInterval.toMillis(),
                TimeUnit.MILLISECONDS);

        activeTasks.put(taskKey, task);
        log.debug("[HEARTBEAT-REGISTERED] resource={}/{} interval={}",
                handle.getResourceType(), handle.getResourceId(), renewalInterval);
    }

    /**
     * Cancels the heartbeat for the given handle.
     *
     * <p>Safe to call even if no heartbeat was registered for the handle.
     */
    public void cancel(LockHandle handle) {
        cancelByKey(heartbeatKey(handle));
    }

    /** Returns the number of active heartbeat tasks (useful for testing). */
    public int activeCount() {
        return activeTasks.size();
    }

    // ── private ───────────────────────────────────────────────────────────────

    private void heartbeatTick(LockHandle handle, Duration extendedTtl, String taskKey) {
        if (handle.isReleased()) {
            log.debug("[HEARTBEAT-SELF-CANCEL] handle already released — stopping heartbeat");
            cancelByKey(taskKey);
            return;
        }
        try {
            boolean extended = lockService.extend(handle, extendedTtl);
            if (!extended) {
                log.warn("[HEARTBEAT-EXTEND-FAILED] resource={}/{} — lock may have expired "
                                + "or been taken by another owner; cancelling heartbeat",
                        handle.getResourceType(), handle.getResourceId());
                cancelByKey(taskKey);
            } else {
                log.debug("[HEARTBEAT-EXTEND-OK] resource={}/{} newTtl={}",
                        handle.getResourceType(), handle.getResourceId(), extendedTtl);
            }
        } catch (Exception ex) {
            log.warn("[HEARTBEAT-ERROR] resource={}/{} err={}",
                    handle.getResourceType(), handle.getResourceId(), ex.getMessage());
        }
    }

    private void cancelByKey(String taskKey) {
        ScheduledFuture<?> task = activeTasks.remove(taskKey);
        if (task != null) {
            task.cancel(false);
        }
    }

    private String heartbeatKey(LockHandle handle) {
        return handle.getResourceType() + ":" + handle.getResourceId()
                + ":" + handle.getLockOwner();
    }
}
