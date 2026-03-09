package com.firstclub.admin.search.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.firstclub.admin.search.SearchResultDTO;
import com.firstclub.platform.redis.RedisJsonCodec;
import com.firstclub.platform.redis.RedisKeyFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Short-lived Redis cache for unified admin search results (Phase 13).
 *
 * <h3>Key pattern</h3>
 * <pre>{@code {env}:firstclub:search:{merchantId}:{sha256(queryType:queryValue)} }</pre>
 *
 * <h3>TTL</h3>
 * {@value #TTL_SECONDS} seconds (30 s).  Search results are volatile — entities
 * can change status quickly during an incident — so the TTL is kept short.
 *
 * <h3>Degradation</h3>
 * All methods are safe when Redis is unavailable: reads return
 * {@link Optional#empty()} and writes are silently ignored.
 *
 * <h3>Security</h3>
 * The cache key uses a SHA-256 digest of {@code queryType:queryValue} rather
 * than the raw query, so sensitive identifiers (e.g. email addresses) are
 * not stored in plaintext in Redis key-space.
 */
@Slf4j
@Component
public class SearchCacheService {

    public static final int TTL_SECONDS = 30;

    private static final TypeReference<List<SearchResultDTO>> LIST_TYPE = new TypeReference<>() {};

    private final ObjectProvider<StringRedisTemplate> templateProvider;
    private final RedisKeyFactory                     keyFactory;
    private final RedisJsonCodec                      codec;

    public SearchCacheService(ObjectProvider<StringRedisTemplate> templateProvider,
                               RedisKeyFactory keyFactory,
                               RedisJsonCodec codec) {
        this.templateProvider = templateProvider;
        this.keyFactory       = keyFactory;
        this.codec            = codec;
    }

    /**
     * Retrieve a cached search result list.
     *
     * @param merchantId  tenant scope
     * @param queryType   logical query dimension label, e.g. {@code "q"}, {@code "invoice"}, etc.
     * @param queryValue  the raw search term
     * @return cached list, or {@link Optional#empty()} on cache miss / Redis unavailable
     */
    public Optional<List<SearchResultDTO>> get(Long merchantId, String queryType, String queryValue) {
        StringRedisTemplate t = template();
        if (t == null) return Optional.empty();
        try {
            String key  = buildKey(merchantId, queryType, queryValue);
            String json = t.opsForValue().get(key);
            if (json == null) return Optional.empty();
            return Optional.ofNullable(codec.fromJson(json, LIST_TYPE));
        } catch (Exception ex) {
            log.warn("SearchCache: read failed merchant={} type={}", merchantId, queryType, ex);
            return Optional.empty();
        }
    }

    /**
     * Store a search result list in the cache.
     *
     * @param merchantId  tenant scope
     * @param queryType   logical query dimension label
     * @param queryValue  the raw search term
     * @param results     list to cache
     */
    public void put(Long merchantId, String queryType, String queryValue, List<SearchResultDTO> results) {
        StringRedisTemplate t = template();
        if (t == null) return;
        try {
            String key = buildKey(merchantId, queryType, queryValue);
            t.opsForValue().set(key, codec.toJson(results), Duration.ofSeconds(TTL_SECONDS));
        } catch (Exception ex) {
            log.warn("SearchCache: write failed merchant={} type={}", merchantId, queryType, ex);
        }
    }

    /**
     * Evict a cached search result (e.g. after an entity mutation).
     */
    public void evict(Long merchantId, String queryType, String queryValue) {
        StringRedisTemplate t = template();
        if (t == null) return;
        try {
            t.delete(buildKey(merchantId, queryType, queryValue));
        } catch (Exception ex) {
            log.warn("SearchCache: evict failed merchant={} type={}", merchantId, queryType, ex);
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private StringRedisTemplate template() {
        return templateProvider.getIfAvailable();
    }

    /**
     * Build the Redis key using a SHA-256 digest of {@code queryType:queryValue}
     * to avoid storing raw PII in the Redis key namespace.
     */
    private String buildKey(Long merchantId, String queryType, String queryValue) {
        String queryHash = sha256Hex(queryType + ":" + queryValue);
        return keyFactory.searchKey(str(merchantId), queryHash);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to exist in all JVMs
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String str(Long value) {
        return value == null ? "null" : value.toString();
    }
}
