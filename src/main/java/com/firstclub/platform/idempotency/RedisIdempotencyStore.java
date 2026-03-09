package com.firstclub.platform.idempotency;

import com.firstclub.platform.redis.RedisAvailabilityService;
import com.firstclub.platform.redis.RedisJsonCodec;
import com.firstclub.platform.redis.RedisKeyFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed fast path for the idempotency layer.
 *
 * <h3>Two operations</h3>
 * <ol>
 *   <li><strong>Response cache</strong> — stores the definitive HTTP response
 *       keyed by {@code {env}:firstclub:idem:resp:{merchantId}:{key}}.
 *       A cache HIT lets the filter replay the response without touching the DB.</li>
 *   <li><strong>Processing lock</strong> — NX-acquired key at
 *       {@code {env}:firstclub:idem:lock:{merchantId}:{key}} with a 30 s TTL.
 *       Ensures that only one concurrent duplicate request proceeds; all others
 *       receive {@code 409 IDEMPOTENCY_IN_PROGRESS} immediately.</li>
 * </ol>
 *
 * <h3>Degradation</h3>
 * <p>All public methods return safe neutral values ({@link Optional#empty()},
 * {@code false}) when Redis is unavailable.  The caller always falls through to
 * the PostgreSQL path transparently.
 *
 * <p><b>Redis is never the source of truth for idempotency guarantees.</b>
 * The PostgreSQL {@code idempotency_keys} table is authoritative.
 */
@Slf4j
@Component
public class RedisIdempotencyStore {

    /** TTL for the in-flight processing lock (seconds). */
    static final int LOCK_TTL_SECONDS = 30;

    private final ObjectProvider<StringRedisTemplate> templateProvider;
    private final RedisKeyFactory keyFactory;
    private final RedisJsonCodec codec;
    private final RedisAvailabilityService availabilityService;

    public RedisIdempotencyStore(ObjectProvider<StringRedisTemplate> templateProvider,
                                 RedisKeyFactory keyFactory,
                                 RedisJsonCodec codec,
                                 RedisAvailabilityService availabilityService) {
        this.templateProvider = templateProvider;
        this.keyFactory = keyFactory;
        this.codec = codec;
        this.availabilityService = availabilityService;
    }

    /** Returns {@code true} only when Redis is configured and currently reachable. */
    public boolean isEnabled() {
        return availabilityService.isAvailable();
    }

    // ── Response cache ────────────────────────────────────────────────────────

    /**
     * Attempts to retrieve a cached completed response from Redis.
     *
     * @param merchantId     the authenticated merchant identifier
     * @param idempotencyKey the raw client-supplied idempotency key
     * @return the cached envelope if present; {@link Optional#empty()} if absent
     *         or Redis is unavailable
     */
    public Optional<IdempotencyResponseEnvelope> tryGetCachedResponse(
            String merchantId, String idempotencyKey) {
        StringRedisTemplate template = resolveTemplate();
        if (template == null) return Optional.empty();
        try {
            String redisKey = keyFactory.idempotencyResponseKey(merchantId, idempotencyKey);
            String json = template.opsForValue().get(redisKey);
            if (json == null) return Optional.empty();
            return codec.tryFromJson(json, IdempotencyResponseEnvelope.class);
        } catch (Exception ex) {
            log.warn("Redis idempotency cache read failed for merchant={} key={}",
                    merchantId, idempotencyKey, ex);
            return Optional.empty();
        }
    }

    /**
     * Caches a completed idempotency response in Redis.
     *
     * <p>Errors are swallowed — a cache miss is always safe (the DB path handles it).
     *
     * @param ttlSeconds time-to-live matching {@code @Idempotent#ttlHours() * 3600}
     */
    public void cacheResponse(String merchantId, String idempotencyKey,
                              IdempotencyResponseEnvelope envelope, long ttlSeconds) {
        StringRedisTemplate template = resolveTemplate();
        if (template == null) return;
        try {
            String redisKey = keyFactory.idempotencyResponseKey(merchantId, idempotencyKey);
            String json = codec.toJson(envelope);
            template.opsForValue().set(redisKey, json, Duration.ofSeconds(ttlSeconds));
            log.debug("Cached idempotency response merchant={} key={} ttl={}s",
                    merchantId, idempotencyKey, ttlSeconds);
        } catch (Exception ex) {
            log.warn("Redis idempotency cache write failed for merchant={} key={}",
                    merchantId, idempotencyKey, ex);
        }
    }

    // ── Processing lock ───────────────────────────────────────────────────────

    /**
     * Attempts to acquire the in-flight processing lock using Redis NX semantics
     * (SET if Not eXists with a {@value LOCK_TTL_SECONDS} s TTL).
     *
     * @return {@code true} if the lock was acquired; {@code false} if it is already
     *         held by another concurrent request or Redis is unavailable
     */
    public boolean tryAcquireLock(String merchantId, String idempotencyKey,
                                  IdempotencyProcessingMarker marker) {
        StringRedisTemplate template = resolveTemplate();
        if (template == null) return false;
        try {
            String lockKey = keyFactory.idempotencyLockKey(merchantId, idempotencyKey);
            String json = codec.toJson(marker);
            Boolean acquired = template.opsForValue()
                    .setIfAbsent(lockKey, json, Duration.ofSeconds(LOCK_TTL_SECONDS));
            return Boolean.TRUE.equals(acquired);
        } catch (Exception ex) {
            log.warn("Redis idempotency lock acquire failed for merchant={} key={}",
                    merchantId, idempotencyKey, ex);
            return false;
        }
    }

    /**
     * Reads the currently-held processing marker without modifying it.
     *
     * @return the marker if a lock is held; {@link Optional#empty()} otherwise
     */
    public Optional<IdempotencyProcessingMarker> getProcessingMarker(
            String merchantId, String idempotencyKey) {
        StringRedisTemplate template = resolveTemplate();
        if (template == null) return Optional.empty();
        try {
            String lockKey = keyFactory.idempotencyLockKey(merchantId, idempotencyKey);
            String json = template.opsForValue().get(lockKey);
            if (json == null) return Optional.empty();
            return codec.tryFromJson(json, IdempotencyProcessingMarker.class);
        } catch (Exception ex) {
            log.warn("Redis idempotency marker read failed for merchant={} key={}",
                    merchantId, idempotencyKey, ex);
            return Optional.empty();
        }
    }

    /**
     * Releases the in-flight processing lock.
     *
     * <p>Safe to call even when Redis is unavailable — the lock will expire
     * naturally after {@value LOCK_TTL_SECONDS} seconds.
     */
    public void releaseLock(String merchantId, String idempotencyKey) {
        StringRedisTemplate template = resolveTemplate();
        if (template == null) return;
        try {
            String lockKey = keyFactory.idempotencyLockKey(merchantId, idempotencyKey);
            template.delete(lockKey);
            log.debug("Released idempotency lock for merchant={} key={}", merchantId, idempotencyKey);
        } catch (Exception ex) {
            log.warn("Redis idempotency lock release failed for merchant={} key={} " +
                     "(will expire naturally after {}s)",
                    merchantId, idempotencyKey, LOCK_TTL_SECONDS, ex);
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Returns the {@link StringRedisTemplate} if Redis is enabled and the bean
     * is available; otherwise {@code null}.
     *
     * <p>Using {@link ObjectProvider} avoids a hard bean dependency when
     * {@code app.redis.enabled=false} and the template bean is absent.
     */
    private StringRedisTemplate resolveTemplate() {
        if (!isEnabled()) return null;
        StringRedisTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            log.debug("StringRedisTemplate not available despite Redis reporting as enabled");
        }
        return template;
    }
}
