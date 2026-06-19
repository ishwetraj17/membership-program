package com.firstclub.membership.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * In-process token-bucket rate limiter (Bucket4j). Buckets live in a bounded, self-expiring
 * Caffeine cache so distinct clients can never grow the map without limit. This is the default
 * strategy — correct for a single node. In a multi-node deployment each pod would keep its own
 * counters, so the {@code redis} profile swaps in {@link RedisRateLimiter} to share the limit.
 */
@Component
@Profile("!redis")
public class LocalRateLimiter implements RateLimiter {

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMinutes(15))
            .build();
    private final long capacity;
    private final Duration refillPeriod;

    public LocalRateLimiter(@Value("${rate-limit.capacity:100}") long capacity,
                            @Value("${rate-limit.refill-period-seconds:60}") long refillSeconds) {
        this.capacity = capacity;
        this.refillPeriod = Duration.ofSeconds(refillSeconds);
    }

    @Override
    public boolean tryConsume(String clientKey) {
        return buckets.get(clientKey, k -> newBucket()).tryConsume(1);
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, refillPeriod)
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
