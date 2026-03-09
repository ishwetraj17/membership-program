package com.firstclub.platform.idempotency.service;

import com.firstclub.platform.idempotency.CachedIdempotencyResponse;
import com.firstclub.platform.idempotency.IdempotencyProcessingMarker;
import com.firstclub.platform.idempotency.IdempotencyResponseEnvelope;
import com.firstclub.platform.idempotency.RedisIdempotencyStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Wraps {@link RedisIdempotencyStore} with Phase-4 enhancements:
 * <ul>
 *   <li>Translates between {@link IdempotencyResponseEnvelope} (legacy) and
 *       {@link CachedIdempotencyResponse} (Phase 4 DTO).</li>
 *   <li>Exposes a configurable {@link RedisFallbackMode} so operators can choose
 *       between hard-failing ({@code REJECT}) or silently degrading to the
 *       Postgres authoritative path ({@code DEGRADE_TO_DB}) when Redis is
 *       unavailable.</li>
 *   <li>Exposes lock-management operations for the filter layer.</li>
 * </ul>
 *
 * <p><b>Redis is never the source of truth.</b>  All methods return safe
 * neutral values when Redis is unavailable.  The caller is responsible for
 * deciding whether to hard-reject or degrade, based on {@link #getFallbackMode()}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyResponseCache {

    /**
     * Controls behaviour when Redis is unavailable.
     */
    public enum RedisFallbackMode {
        /** Return HTTP 503 to the caller when Redis is unavailable. */
        REJECT,
        /** Fall through to the PostgreSQL authoritative record when Redis is unavailable. */
        DEGRADE_TO_DB
    }

    private final RedisIdempotencyStore redisStore;

    @Value("${app.idempotency.redis-failure-mode:DEGRADE_TO_DB}")
    private RedisFallbackMode fallbackMode;

    /** Returns {@code true} when Redis is configured and currently reachable. */
    public boolean isAvailable() {
        return redisStore.isEnabled();
    }

    /** The configured Redis failure mode. */
    public RedisFallbackMode getFallbackMode() {
        return fallbackMode;
    }

    /**
     * Retrieves a cached completed response from Redis.
     *
     * @return empty if Redis is unavailable or no cached entry exists
     */
    public Optional<CachedIdempotencyResponse> get(String merchantId, String idempotencyKey) {
        if (!redisStore.isEnabled()) return Optional.empty();
        return redisStore.tryGetCachedResponse(merchantId, idempotencyKey)
                .map(env -> new CachedIdempotencyResponse(
                        env.requestHash(), env.endpointSignature(), env.statusCode(),
                        env.responseBody(), env.contentType(),
                        null /* no originalAt in legacy envelope */));
    }

    /**
     * Caches a completed response in Redis with the given TTL (in seconds).
     * Silently ignores when Redis is unavailable.
     */
    public void put(String merchantId, String idempotencyKey,
                    CachedIdempotencyResponse response, long ttlSeconds) {
        if (!redisStore.isEnabled()) return;
        IdempotencyResponseEnvelope env = new IdempotencyResponseEnvelope(
                response.requestHash(), response.endpointSignature(),
                response.statusCode(), response.responseBody(), response.contentType());
        redisStore.cacheResponse(merchantId, idempotencyKey, env, ttlSeconds);
    }

    /**
     * Attempts to acquire the NX in-flight processing lock for a key.
     *
     * @return {@code true} if the lock was acquired; {@code false} if another
     *         request already holds the lock or Redis is unavailable
     */
    public boolean tryAcquireLock(String merchantId, String key,
                                   IdempotencyProcessingMarker marker) {
        return redisStore.tryAcquireLock(merchantId, key, marker);
    }

    /** Releases the in-flight processing lock. No-op when Redis is unavailable. */
    public void releaseLock(String merchantId, String key) {
        redisStore.releaseLock(merchantId, key);
    }

    /**
     * Returns the processing marker if the in-flight lock is currently held.
     *
     * @return empty if no lock is held or Redis is unavailable
     */
    public Optional<IdempotencyProcessingMarker> getProcessingMarker(
            String merchantId, String key) {
        return redisStore.getProcessingMarker(merchantId, key);
    }
}
