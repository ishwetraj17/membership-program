package com.firstclub.membership.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Local (in-process) rate limiter — token bucket")
class LocalRateLimiterTest {

    /** Long refill window so the bucket does not replenish mid-test. */
    private LocalRateLimiter limiter(long capacity) {
        return new LocalRateLimiter(capacity, 3600);
    }

    @Test @DisplayName("allows up to capacity then rejects further requests")
    void enforcesCapacity() {
        LocalRateLimiter limiter = limiter(3);
        assertThat(limiter.tryConsume("ip:1.1.1.1")).isTrue();
        assertThat(limiter.tryConsume("ip:1.1.1.1")).isTrue();
        assertThat(limiter.tryConsume("ip:1.1.1.1")).isTrue();
        assertThat(limiter.tryConsume("ip:1.1.1.1")).isFalse(); // 4th over a capacity of 3
    }

    @Test @DisplayName("each client key gets an independent bucket")
    void keysAreIsolated() {
        LocalRateLimiter limiter = limiter(1);
        assertThat(limiter.tryConsume("user:alice")).isTrue();
        assertThat(limiter.tryConsume("user:alice")).isFalse(); // alice exhausted
        assertThat(limiter.tryConsume("user:bob")).isTrue();    // bob unaffected
    }
}
