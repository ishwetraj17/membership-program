package com.firstclub.platform.ratelimit;

import com.firstclub.platform.redis.RedisAvailabilityService;
import com.firstclub.platform.redis.RedisKeyFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limit service based on a <strong>sliding window log</strong> implemented
 * with a Redis sorted set and an atomic Lua script.
 *
 * <h3>Algorithm</h3>
 * For each rate limit check, the Lua script atomically:
 * <ol>
 *   <li>{@code ZREMRANGEBYSCORE key -inf (now - windowMs)} — evict expired entries</li>
 *   <li>{@code ZCARD key} — count current entries</li>
 *   <li>If {@code count < limit}: {@code ZADD key now reqId} + {@code PEXPIRE key windowMs}
 *       → returns {@code [1, remaining, resetMs]}</li>
 *   <li>Otherwise: inspect oldest entry → returns {@code [0, 0, resetMs]}</li>
 * </ol>
 * Using a sorted set means the window slides smoothly: old entries age out
 * continuously.  The {@code PEXPIRE} on every admission ensures the key is
 * automatically cleaned up by Redis even if traffic stops entirely.
 *
 * <h3>Graceful degradation</h3>
 * When Redis is unavailable ({@code isEnabled() == false}), every check
 * returns a permissive {@link RateLimitDecision} so legitimate traffic is
 * never blocked by an infrastructure outage.  Blocked request counts are
 * tracked in-JVM for the deep-health endpoint.
 */
@Slf4j
@Service
public class RedisSlidingWindowRateLimiter implements RateLimitService {

    /**
     * Atomic Lua script for the sliding window check.
     *
     * <p>KEYS[1]  = sorted-set key<br>
     * ARGV[1]  = now in milliseconds (epoch)<br>
     * ARGV[2]  = window size in milliseconds<br>
     * ARGV[3]  = request limit<br>
     * ARGV[4]  = unique member ID (prevents ZADD collision on same-ms requests)<br>
     *
     * <p>Returns a three-element list:
     * <ul>
     *   <li>[0] = 1 (allowed) or 0 (denied)</li>
     *   <li>[1] = remaining count (0 when denied)</li>
     *   <li>[2] = reset timestamp in epoch-ms</li>
     * </ul>
     */
    // language=lua
    static final String SLIDING_WINDOW_LUA = """
            local key     = KEYS[1]
            local now     = tonumber(ARGV[1])
            local winMs   = tonumber(ARGV[2])
            local lim     = tonumber(ARGV[3])
            local reqId   = ARGV[4]
            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - winMs)
            local cnt = redis.call('ZCARD', key)
            if cnt < lim then
                redis.call('ZADD', key, now, reqId)
                redis.call('PEXPIRE', key, winMs)
                return {1, lim - cnt - 1, now + winMs}
            else
                local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
                local resetMs = now + winMs
                if #oldest >= 2 then resetMs = tonumber(oldest[2]) + winMs end
                return {0, 0, resetMs}
            end
            """;

    private final ObjectProvider<StringRedisTemplate> templateProvider;
    private final RedisKeyFactory                      keyFactory;
    private final RedisAvailabilityService             availabilityService;
    private final RateLimitProperties                  properties;
    private final RateLimitEventRepository             eventRepository;

    /** Accumulated block count since JVM start — exposed via deep health. */
    private final AtomicLong totalBlockCount = new AtomicLong(0);

    private final RedisScript<List<Long>> slidingWindowScript;

    @Autowired
    public RedisSlidingWindowRateLimiter(
            ObjectProvider<StringRedisTemplate> templateProvider,
            RedisKeyFactory keyFactory,
            RedisAvailabilityService availabilityService,
            RateLimitProperties properties,
            RateLimitEventRepository eventRepository) {
        this.templateProvider    = templateProvider;
        this.keyFactory          = keyFactory;
        this.availabilityService = availabilityService;
        this.properties          = properties;
        this.eventRepository     = eventRepository;
        // Create the script definition once — SHA1 is sent only on first eval,
        // subsequent calls use EVALSHA for lower overhead.
        @SuppressWarnings("unchecked")
        RedisScript<List<Long>> script =
                (RedisScript<List<Long>>) (RedisScript<?>) RedisScript.of(SLIDING_WINDOW_LUA, List.class);
        this.slidingWindowScript = script;
    }

    // ── RateLimitService implementation ───────────────────────────────────────

    @Override
    public boolean isEnabled() {
        return properties.isEnabled() && availabilityService.isAvailable();
    }

    @Override
    public RateLimitDecision checkLimit(RateLimitPolicy policy, String... subjects) {
        String key = buildKey(policy, subjects);

        if (!isEnabled()) {
            log.trace("Rate limiting disabled — permitting {} for key {}", policy, key);
            return RateLimitDecision.permissive(policy, key);
        }

        StringRedisTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            log.warn("StringRedisTemplate unavailable — permitting {} for key {}", policy, key);
            return RateLimitDecision.permissive(policy, key);
        }

        int  limit    = properties.resolveLimit(policy);
        long windowMs = properties.resolveWindow(policy).toMillis();
        long nowMs    = System.currentTimeMillis();
        String reqId  = UUID.randomUUID().toString();

        try {
            @SuppressWarnings("unchecked")
            List<Long> result = template.execute(
                    slidingWindowScript,
                    List.of(key),
                    String.valueOf(nowMs),
                    String.valueOf(windowMs),
                    String.valueOf(limit),
                    reqId);

            if (result == null || result.size() < 3) {
                log.warn("Unexpected Lua result for key {} — permitting", key);
                return RateLimitDecision.permissive(policy, key);
            }

            boolean allowed   = result.get(0) == 1L;
            int     remaining = (int) Math.max(0, result.get(1));
            Instant resetAt   = Instant.ofEpochMilli(result.get(2));

            if (allowed) {
                return RateLimitDecision.permit(policy, key, limit, remaining, resetAt);
            } else {
                totalBlockCount.incrementAndGet();
                persistBlockEvent(policy, key, subjects, reqId);
                return RateLimitDecision.deny(policy, key, limit, resetAt);
            }
        } catch (Exception e) {
            log.warn("Rate limit check failed for key {} ({}): {} — permitting",
                    key, policy, e.getMessage());
            return RateLimitDecision.permissive(policy, key);
        }
    }

    // ── Public metrics accessors ──────────────────────────────────────────────

    /** Total blocked requests since JVM start. Used by deep health endpoint. */
    public long getTotalBlockCount() {
        return totalBlockCount.get();
    }

    /** Blocks recorded in the audit table in the past hour. */
    public long getBlocksLastHour() {
        try {
            return eventRepository.countBlocksLastHour();
        } catch (Exception e) {
            log.debug("Could not query block count from audit table: {}", e.getMessage());
            return totalBlockCount.get();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds the Redis key for a given policy and subjects.
     *
     * <p>Routes to the correct {@link RedisKeyFactory} method so that
     * every key conforms to the canonical namespace documented in
     * {@code docs/performance/02-redis-usage.md}.
     */
    String buildKey(RateLimitPolicy policy, String... subjects) {
        return switch (policy) {
            case AUTH_BY_IP      -> keyFactory.rateLimitAuthIpKey(safeGet(subjects, 0));
            case AUTH_BY_EMAIL   -> keyFactory.rateLimitAuthEmailKey(safeGet(subjects, 0));
            case PAYMENT_CONFIRM -> keyFactory.rateLimitPayConfirmKey(
                    safeGet(subjects, 0), safeGet(subjects, 1));
            case WEBHOOK_INGEST  -> keyFactory.rateLimitWebhookKey(
                    safeGet(subjects, 0), safeGet(subjects, 1));
            case APIKEY_GENERAL  -> keyFactory.rateLimitApiKeyKey(
                    safeGet(subjects, 0), safeGet(subjects, 1));
        };
    }

    private static String safeGet(String[] arr, int idx) {
        return (arr != null && idx < arr.length && arr[idx] != null) ? arr[idx] : "unknown";
    }

    /** Asynchronously persist a block event for audit / ops dashboards. */
    private void persistBlockEvent(RateLimitPolicy policy, String key,
                                   String[] subjects, String requestId) {
        try {
            RateLimitEventEntity event = RateLimitEventEntity.builder()
                    .category(policy.name())
                    .subjectKey(key)
                    .merchantId(subjects.length > 0 ? subjects[0] : null)
                    .blocked(true)
                    .requestId(requestId)
                    .reason("Sliding window limit exceeded for policy " + policy.name())
                    .build();
            eventRepository.save(event);
        } catch (Exception e) {
            // Audit persistence failure must never affect the rate limit decision
            log.debug("Failed to persist rate limit block event: {}", e.getMessage());
        }
    }
}
