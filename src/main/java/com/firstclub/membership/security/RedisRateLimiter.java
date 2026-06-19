package com.firstclub.membership.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis-backed token-bucket rate limiter — activated by the {@code redis} profile so all pods in a
 * horizontally-scaled deployment share one counter per client (a user hitting two pods still sees a
 * single combined limit).
 *
 * <p>The bucket math is identical to {@link LocalRateLimiter}'s Bucket4j greedy refill — {@code
 * capacity} tokens replenished smoothly over {@code refill-period} — so switching strategies moves
 * the counter into Redis without changing the limit. The check is a single Lua script evaluated
 * atomically on the Redis server, so the read-refill-consume sequence cannot race across pods.
 */
@Component
@Profile("redis")
public class RedisRateLimiter implements RateLimiter {

    /**
     * Atomic token bucket. KEYS = [tokens, timestamp]; ARGV = [capacity, periodMillis, nowMillis,
     * requested]. Returns 1 if a token was consumed, 0 otherwise. Keys self-expire after two refill
     * windows of inactivity so idle clients leave nothing behind.
     */
    private static final RedisScript<Long> TOKEN_BUCKET = new DefaultRedisScript<>("""
            local tokensKey = KEYS[1]
            local tsKey = KEYS[2]
            local capacity = tonumber(ARGV[1])
            local periodMs = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])

            local tokens = tonumber(redis.call('get', tokensKey))
            local last = tonumber(redis.call('get', tsKey))
            if tokens == nil then
              tokens = capacity
              last = now
            end

            if now > last then
              tokens = math.min(capacity, tokens + (now - last) * capacity / periodMs)
              last = now
            end

            local allowed = 0
            if tokens >= requested then
              tokens = tokens - requested
              allowed = 1
            end

            local ttl = periodMs * 2
            redis.call('set', tokensKey, tokens, 'PX', ttl)
            redis.call('set', tsKey, last, 'PX', ttl)
            return allowed
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String capacity;
    private final String periodMillis;

    public RedisRateLimiter(StringRedisTemplate redis,
                            @Value("${rate-limit.capacity:100}") long capacity,
                            @Value("${rate-limit.refill-period-seconds:60}") long refillSeconds) {
        this.redis = redis;
        this.capacity = Long.toString(capacity);
        this.periodMillis = Long.toString(refillSeconds * 1000);
    }

    @Override
    public boolean tryConsume(String clientKey) {
        // {clientKey} hash-tag keeps both keys in the same Redis Cluster slot so the script's
        // multi-key access is always local to one node.
        String tokensKey = "ratelimit:{" + clientKey + "}:tokens";
        String tsKey = "ratelimit:{" + clientKey + "}:ts";
        Long allowed = redis.execute(TOKEN_BUCKET, List.of(tokensKey, tsKey),
                capacity, periodMillis, Long.toString(System.currentTimeMillis()), "1");
        return allowed != null && allowed == 1L;
    }
}
