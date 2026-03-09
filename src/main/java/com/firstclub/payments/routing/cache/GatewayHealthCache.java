package com.firstclub.payments.routing.cache;

import com.firstclub.payments.routing.dto.GatewayHealthResponseDTO;
import com.firstclub.platform.redis.RedisJsonCodec;
import com.firstclub.platform.redis.RedisKeyFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed cache for gateway health snapshots.
 *
 * <h3>Key pattern</h3>
 * <pre>{@code {env}:firstclub:gw:health:{GATEWAY_NAME} }</pre>
 *
 * <h3>Value</h3>
 * JSON-serialised {@link GatewayHealthResponseDTO}.
 *
 * <h3>TTL</h3>
 * {@value #TTL_SECONDS} seconds (60 s).  The short TTL allows automated probes to
 * push fresh readings through without an explicit eviction call.  Callers should
 * also invoke {@link #put} immediately after a DB update so the cache reflects
 * changes within the same request.
 *
 * <h3>Degradation</h3>
 * All methods are safe to call when Redis is unavailable: {@link #get} returns
 * {@link Optional#empty()} and write/evict calls are silently ignored.
 *
 * <p><b>Redis is never the source of truth for gateway health.</b>
 * The {@code gateway_health} table is authoritative.  Every {@link #get} caller
 * must implement a DB fallback.
 */
@Slf4j
@Component
public class GatewayHealthCache {

    /** TTL applied to every gateway-health cache entry. */
    public static final int TTL_SECONDS = 60;

    private final ObjectProvider<StringRedisTemplate> templateProvider;
    private final RedisKeyFactory keyFactory;
    private final RedisJsonCodec codec;

    public GatewayHealthCache(ObjectProvider<StringRedisTemplate> templateProvider,
                               RedisKeyFactory keyFactory,
                               RedisJsonCodec codec) {
        this.templateProvider = templateProvider;
        this.keyFactory = keyFactory;
        this.codec = codec;
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Returns the cached health snapshot for the given gateway, or
     * {@link Optional#empty()} on a cache miss or when Redis is unavailable.
     *
     * @param gatewayName case-insensitive gateway identifier (e.g. {@code "razorpay"})
     */
    public Optional<GatewayHealthResponseDTO> get(String gatewayName) {
        StringRedisTemplate template = resolveTemplate();
        if (template == null) return Optional.empty();
        try {
            String key = keyFactory.gatewayHealthKey(gatewayName);
            String json = template.opsForValue().get(key);
            if (json == null) return Optional.empty();
            return codec.tryFromJson(json, GatewayHealthResponseDTO.class);
        } catch (Exception ex) {
            log.warn("GatewayHealthCache: read failed for gateway={}", gatewayName, ex);
            return Optional.empty();
        }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Stores (or refreshes) the health snapshot for a gateway.
     * Errors are swallowed — the DB is the source of truth.
     *
     * @param gatewayName case-insensitive gateway identifier
     * @param snapshot    the DTO to cache
     */
    public void put(String gatewayName, GatewayHealthResponseDTO snapshot) {
        StringRedisTemplate template = resolveTemplate();
        if (template == null) return;
        try {
            String key = keyFactory.gatewayHealthKey(gatewayName);
            String json = codec.toJson(snapshot);
            template.opsForValue().set(key, json, Duration.ofSeconds(TTL_SECONDS));
            log.debug("GatewayHealthCache: cached health for gateway={} ttl={}s",
                    gatewayName, TTL_SECONDS);
        } catch (Exception ex) {
            log.warn("GatewayHealthCache: write failed for gateway={}", gatewayName, ex);
        }
    }

    // ── Evict ─────────────────────────────────────────────────────────────────

    /**
     * Removes the cached entry for the given gateway.
     * Safe to call when Redis is unavailable — errors are silently ignored.
     */
    public void evict(String gatewayName) {
        StringRedisTemplate template = resolveTemplate();
        if (template == null) return;
        try {
            template.delete(keyFactory.gatewayHealthKey(gatewayName));
            log.debug("GatewayHealthCache: evicted gateway={}", gatewayName);
        } catch (Exception ex) {
            log.warn("GatewayHealthCache: evict failed for gateway={}", gatewayName, ex);
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private StringRedisTemplate resolveTemplate() {
        StringRedisTemplate t = templateProvider.getIfAvailable();
        if (t == null) {
            log.trace("GatewayHealthCache: Redis not available — skipping cache operation");
        }
        return t;
    }
}
