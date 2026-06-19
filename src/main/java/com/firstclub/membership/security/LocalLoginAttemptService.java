package com.firstclub.membership.security;

import com.firstclub.membership.config.SecurityProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * In-process login-lockout tracking (Caffeine). The default strategy — correct on a single node.
 * Entries self-expire after the lockout window, so the lock lifts automatically and the map can't
 * grow without bound. The {@code redis} profile swaps in {@link RedisLoginAttemptService} so the
 * lock is shared across pods.
 */
@Component
@Profile("!redis")
public class LocalLoginAttemptService implements LoginAttemptService {

    private final int maxAttempts;
    /** Per-username failed-login counter; entries self-expire after the lockout window. */
    private final Cache<String, Integer> failedAttempts;

    public LocalLoginAttemptService(SecurityProperties securityProperties) {
        this.maxAttempts = securityProperties.getLockout().getMaxAttempts();
        this.failedAttempts = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(securityProperties.getLockout().getWindowMinutes()))
                .build();
    }

    @Override
    public boolean isLockedOut(String username) {
        Integer fails = failedAttempts.getIfPresent(username);
        return fails != null && fails >= maxAttempts;
    }

    @Override
    public void recordFailure(String username) {
        Integer fails = failedAttempts.getIfPresent(username);
        failedAttempts.put(username, (fails == null ? 0 : fails) + 1);
    }

    @Override
    public void recordSuccess(String username) {
        failedAttempts.invalidate(username);
    }
}
