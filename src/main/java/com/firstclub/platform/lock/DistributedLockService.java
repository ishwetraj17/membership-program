package com.firstclub.platform.lock;

import com.firstclub.platform.lock.fencing.FencingTokenService;
import com.firstclub.platform.lock.metrics.LockMetricsService;
import com.firstclub.platform.lock.redis.LockOwnerIdentityProvider;
import com.firstclub.platform.lock.redis.LockScriptRegistry;
import com.firstclub.platform.redis.RedisKeyFactory;
import com.firstclub.platform.redis.RedisOpsFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Production-grade Redis-backed distributed lock service.
 *
 * <h3>Acquire → fence → work → release pattern</h3>
 * <ol>
 *   <li>Call {@link #acquireWithRetry} to obtain a {@link LockHandle}.</li>
 *   <li>Read {@link LockHandle#getFenceToken()} — pass this token to every
 *       downstream DB write so the storage layer can reject stale updates.</li>
 *   <li>Perform work inside a {@code try-with-resources} block.</li>
 *   <li>{@link LockHandle#close()} releases the lock via the Lua
 *       "release-if-owner" script; a concurrent process that acquired the lock
 *       after TTL expiry will not have its lock deleted.</li>
 * </ol>
 *
 * <h3>Why Lua scripts?</h3>
 * A naive {@code DEL lockKey} deletes the lock regardless of who owns it.
 * If the original lock holder is paused (GC pause, network partition) and the
 * TTL expires, a second thread acquires the lock.  When the first thread
 * resumes and calls {@code DEL}, it deletes the second thread's lock — leaving
 * it unprotected.  All three operations (acquire, extend, release) use atomic
 * Lua scripts that first check ownership before mutating any key.
 *
 * <h3>Redis availability</h3>
 * When Redis is unavailable, {@link #tryAcquire} returns
 * {@link LockAcquisitionResult.Status#FAILED_REDIS_UNAVAILABLE} and
 * {@link #acquireWithRetry} throws immediately.
 */
@Slf4j
@Service
public class DistributedLockService {

    private static final long RETRY_BASE_DELAY_MS  = 50L;
    private static final double RETRY_JITTER_FRAC  = 0.3;

    private final LockScriptRegistry      scripts;
    private final RedisKeyFactory         keyFactory;
    private final LockOwnerIdentityProvider ownerProvider;
    private final FencingTokenService     fencingTokenService;
    private final LockMetricsService      metricsService;
    private final ObjectProvider<RedisOpsFacade> redisProvider;

    public DistributedLockService(LockScriptRegistry scripts,
                                   RedisKeyFactory keyFactory,
                                   LockOwnerIdentityProvider ownerProvider,
                                   FencingTokenService fencingTokenService,
                                   LockMetricsService metricsService,
                                   ObjectProvider<RedisOpsFacade> redisProvider) {
        this.scripts          = scripts;
        this.keyFactory       = keyFactory;
        this.ownerProvider    = ownerProvider;
        this.fencingTokenService = fencingTokenService;
        this.metricsService   = metricsService;
        this.redisProvider    = redisProvider;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Non-blocking attempt to acquire the lock.
     *
     * @param resourceType human-readable entity type, e.g. {@code "subscription"}
     * @param resourceId   entity identifier, e.g. {@code "42"}
     * @param ttl          lock time-to-live; the lock expires automatically after
     *                     this duration if not explicitly released or extended
     * @return result indicating whether the lock was acquired
     */
    public LockAcquisitionResult tryAcquire(String resourceType, String resourceId,
                                             Duration ttl) {
        RedisOpsFacade ops = redisProvider.getIfAvailable();
        if (ops == null) {
            metricsService.recordAcquisitionFailure(resourceType, resourceId, "REDIS_UNAVAILABLE");
            return LockAcquisitionResult.redisUnavailable();
        }

        String lockKey    = keyFactory.distributedLockKey(resourceType, resourceId);
        String ownerToken = ownerProvider.generateOwnerToken();
        String ttlMs      = String.valueOf(ttl.toMillis());

        Long acquired = ops.runLuaScript(
                scripts.acquireScript(), List.of(lockKey), new Object[]{ownerToken, ttlMs});

        if (!Long.valueOf(1L).equals(acquired)) {
            log.debug("[LOCK-BUSY] resource={}/{}", resourceType, resourceId);
            return LockAcquisitionResult.alreadyLocked();
        }

        // Fence token: generated after lock acquisition to reflect correct ordering
        long fenceToken;
        try {
            fenceToken = fencingTokenService.generateToken(resourceType, resourceId);
        } catch (Exception ex) {
            // Roll back: release the lock we just acquired
            ops.runLuaScript(scripts.releaseScript(), List.of(lockKey), new Object[]{ownerToken});
            log.warn("[LOCK-FENCE-FAILED] resource={}/{} err={}",
                    resourceType, resourceId, ex.getMessage());
            metricsService.recordAcquisitionFailure(resourceType, resourceId, "FENCE_TOKEN_FAILED");
            return LockAcquisitionResult.redisUnavailable();
        }

        LockHandle handle = new LockHandle(
                resourceType, resourceId, lockKey, ownerToken, fenceToken,
                Instant.now(),
                () -> releaseByKeyAndOwner(lockKey, ownerToken));

        metricsService.recordAcquisition(resourceType, resourceId, fenceToken, Duration.ZERO);
        return LockAcquisitionResult.acquired(handle);
    }

    /**
     * Blocking acquisition with back-off retry until {@code timeout} elapses.
     *
     * <p>Callers prefer this for high-value resources (subscription renewal,
     * refund processing) where not acquiring the lock is never acceptable.
     *
     * @param resourceType entity type
     * @param resourceId   entity identifier
     * @param ttl          lock lease duration
     * @param timeout      maximum time to wait before throwing
     * @return the acquired {@link LockHandle}; release via {@code try-with-resources}
     * @throws LockAcquisitionTimeoutException if the lock could not be acquired
     *         within {@code timeout}
     */
    public LockHandle acquireWithRetry(String resourceType, String resourceId,
                                        Duration ttl, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        int attempts     = 0;

        while (true) {
            attempts++;
            LockAcquisitionResult result = tryAcquire(resourceType, resourceId, ttl);

            if (result.isAcquired()) {
                return result.lockHandle().orElseThrow();
            }

            if (result.getStatus() == LockAcquisitionResult.Status.FAILED_REDIS_UNAVAILABLE) {
                metricsService.recordAcquisitionTimeout(resourceType, resourceId, attempts);
                throw new LockAcquisitionTimeoutException(resourceType, resourceId, attempts, timeout);
            }

            if (Instant.now().isAfter(deadline)) {
                metricsService.recordAcquisitionTimeout(resourceType, resourceId, attempts);
                throw new LockAcquisitionTimeoutException(resourceType, resourceId, attempts, timeout);
            }

            sleepQuietly(computeRetryDelay(attempts));
        }
    }

    /**
     * Extends the lock TTL — only succeeds if the caller still owns the lock.
     *
     * <p>Used by {@link LockLeaseHeartbeat} to keep long-running operations
     * from losing their lock to TTL expiry.
     *
     * @param handle  the active lock handle
     * @param newTtl  new TTL from now
     * @return {@code true} if the extension succeeded; {@code false} if the
     *         lock was already expired or taken by another owner
     */
    public boolean extend(LockHandle handle, Duration newTtl) {
        if (handle.isReleased()) return false;
        RedisOpsFacade ops = redisProvider.getIfAvailable();
        if (ops == null) return false;

        String ttlMs  = String.valueOf(newTtl.toMillis());
        Long   result = ops.runLuaScript(
                scripts.extendScript(),
                List.of(handle.getLockKey()),
                new Object[]{handle.getLockOwner(), ttlMs});

        boolean ok = Long.valueOf(1L).equals(result);
        metricsService.recordExtension(handle.getResourceType(), handle.getResourceId(), ok);
        return ok;
    }

    /**
     * Closes the handle, which triggers the Lua-safe release of the lock.
     *
     * <p>Equivalent to calling {@code handle.close()} directly.  Provided as
     * an explicit method so service code can release without a try-with-resources.
     */
    public void release(LockHandle handle) {
        handle.close();
    }

    // ── Package-private — used by tests and LockLeaseHeartbeat ───────────────

    /**
     * Low-level release: executes the RELEASE Lua script for an explicit
     * {@code lockKey} / {@code ownerToken} pair.
     *
     * <p>Returns {@code true} only if the key existed and matched the given
     * owner.  Any wrong-owner or already-expired call returns {@code false}.
     */
    boolean releaseByKeyAndOwner(String lockKey, String ownerToken) {
        RedisOpsFacade ops = redisProvider.getIfAvailable();
        if (ops == null) return false;

        Long result  = ops.runLuaScript(
                scripts.releaseScript(), List.of(lockKey), new Object[]{ownerToken});
        boolean deleted = Long.valueOf(1L).equals(result);
        log.debug("[LOCK-RELEASE] key={} owner={} deleted={}", lockKey, ownerToken, deleted);
        return deleted;
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private long computeRetryDelay(int attempt) {
        long base   = (long) (RETRY_BASE_DELAY_MS * Math.pow(2.0, attempt - 1));
        double jitter = ThreadLocalRandom.current().nextDouble() * RETRY_JITTER_FRAC * base;
        return base + (long) jitter;
    }

    private void sleepQuietly(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
