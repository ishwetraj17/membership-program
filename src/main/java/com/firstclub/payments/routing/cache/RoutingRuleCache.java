package com.firstclub.payments.routing.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.firstclub.payments.routing.dto.GatewayRouteRuleResponseDTO;
import com.firstclub.platform.redis.RedisJsonCodec;
import com.firstclub.platform.redis.RedisKeyFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Redis-backed cache for active gateway routing rules.
 *
 * <h3>Key pattern</h3>
 * <pre>{@code {env}:firstclub:routing:{scope}:{methodType}:{currency}:{retryNumber} }</pre>
 *
 * Where {@code scope} is either the string representation of the merchant-account ID
 * (e.g. {@code "42"}) or the literal {@code "global"} for platform-wide defaults.
 *
 * <h3>Value</h3>
 * JSON-serialised {@code List<GatewayRouteRuleResponseDTO>} ordered by rule priority (ascending).
 *
 * <h3>TTL</h3>
 * {@value #TTL_SECONDS} seconds (5 min).  Routing rules change infrequently; the TTL provides
 * a safety net even if an explicit eviction is skipped.
 *
 * <h3>Cache invalidation</h3>
 * Callers must call {@link #evict} after any create, update, or deactivate operation on a
 * {@code GatewayRouteRule}.  The {methodType, currency, retryNumber} discriminators are fixed
 * at rule-creation time, so targeted eviction of a single key is always possible.
 *
 * <h3>Degradation</h3>
 * All methods are safe when Redis is unavailable: {@link #get} returns {@link Optional#empty()}
 * and write/evict calls are silently ignored.
 *
 * <p><b>Redis is never the source of truth for routing rules.</b>
 * The {@code gateway_route_rules} table is authoritative.  Every {@link #get} caller must
 * implement a DB fallback.
 */
@Slf4j
@Component
public class RoutingRuleCache {

    /** TTL applied to every routing-rule cache entry. */
    public static final int TTL_SECONDS = 300; // 5 minutes

    private static final TypeReference<List<GatewayRouteRuleResponseDTO>> RULE_LIST_TYPE =
            new TypeReference<>() {};

    private final ObjectProvider<StringRedisTemplate> templateProvider;
    private final RedisKeyFactory keyFactory;
    private final RedisJsonCodec codec;

    public RoutingRuleCache(ObjectProvider<StringRedisTemplate> templateProvider,
                             RedisKeyFactory keyFactory,
                             RedisJsonCodec codec) {
        this.templateProvider = templateProvider;
        this.keyFactory = keyFactory;
        this.codec = codec;
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Returns the cached routing rules for the given discriminator combination, or
     * {@link Optional#empty()} on a cache miss or when Redis is unavailable.
     *
     * @param scope        merchant-id string or {@code "global"}
     * @param methodType   payment method type (e.g. {@code "CARD"})
     * @param currency     ISO-4217 currency code (e.g. {@code "INR"})
     * @param retryNumber  attempt number (1-based)
     */
    public Optional<List<GatewayRouteRuleResponseDTO>> get(String scope, String methodType,
                                                            String currency, int retryNumber) {
        StringRedisTemplate template = resolveTemplate();
        if (template == null) return Optional.empty();
        try {
            String key = keyFactory.routingRuleCacheKey(scope, methodType, currency, retryNumber);
            String json = template.opsForValue().get(key);
            if (json == null) return Optional.empty();
            List<GatewayRouteRuleResponseDTO> rules = codec.fromJson(json, RULE_LIST_TYPE);
            if (rules == null) return Optional.empty();
            log.debug("RoutingRuleCache: HIT scope={} method={} currency={} retry={}",
                    scope, methodType, currency, retryNumber);
            return Optional.of(rules);
        } catch (Exception ex) {
            log.warn("RoutingRuleCache: read failed scope={} method={} currency={} retry={}",
                    scope, methodType, currency, retryNumber, ex);
            return Optional.empty();
        }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Stores the routing rules for a given discriminator combination.
     * Errors are swallowed — the DB is the source of truth.
     *
     * @param scope        merchant-id string or {@code "global"}
     * @param methodType   payment method type
     * @param currency     ISO-4217 currency code
     * @param retryNumber  attempt number (1-based)
     * @param rules        ordered list of routing rules (priority ASC) to cache
     */
    public void put(String scope, String methodType, String currency, int retryNumber,
                    List<GatewayRouteRuleResponseDTO> rules) {
        StringRedisTemplate template = resolveTemplate();
        if (template == null) return;
        try {
            String key = keyFactory.routingRuleCacheKey(scope, methodType, currency, retryNumber);
            String json = codec.toJson(rules);
            template.opsForValue().set(key, json, Duration.ofSeconds(TTL_SECONDS));
            log.debug("RoutingRuleCache: cached {} rule(s) scope={} method={} currency={} retry={} ttl={}s",
                    rules.size(), scope, methodType, currency, retryNumber, TTL_SECONDS);
        } catch (Exception ex) {
            log.warn("RoutingRuleCache: write failed scope={} method={} currency={} retry={}",
                    scope, methodType, currency, retryNumber, ex);
        }
    }

    // ── Evict ─────────────────────────────────────────────────────────────────

    /**
     * Removes a single cached entry for the exact discriminator combination.
     * Call this after creating, updating, or deactivating a routing rule.
     * Safe to call when Redis is unavailable — errors are silently ignored.
     *
     * @param scope        merchant-id string or {@code "global"}
     * @param methodType   payment method type
     * @param currency     ISO-4217 currency code
     * @param retryNumber  attempt number (1-based)
     */
    public void evict(String scope, String methodType, String currency, int retryNumber) {
        StringRedisTemplate template = resolveTemplate();
        if (template == null) return;
        try {
            String key = keyFactory.routingRuleCacheKey(scope, methodType, currency, retryNumber);
            template.delete(key);
            log.debug("RoutingRuleCache: evicted scope={} method={} currency={} retry={}",
                    scope, methodType, currency, retryNumber);
        } catch (Exception ex) {
            log.warn("RoutingRuleCache: evict failed scope={} method={} currency={} retry={}",
                    scope, methodType, currency, retryNumber, ex);
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private StringRedisTemplate resolveTemplate() {
        StringRedisTemplate t = templateProvider.getIfAvailable();
        if (t == null) {
            log.trace("RoutingRuleCache: Redis not available — skipping cache operation");
        }
        return t;
    }
}
