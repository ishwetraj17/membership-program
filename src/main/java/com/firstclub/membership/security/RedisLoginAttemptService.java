package com.firstclub.membership.security;

import com.firstclub.membership.config.SecurityProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis-backed login-lockout tracking — activated by the {@code redis} profile so the failure
 * counter is shared across all pods. Without this, an attacker could spread guesses across
 * instances and never trip a single node's local counter.
 *
 * <p>Each failure atomically does {@code INCR} + {@code PEXPIRE(window)} on a per-username key,
 * mirroring the single-node {@code expireAfterWrite} semantics (the window restarts on every
 * failure), and a successful login deletes the key. The threshold and window come from the same
 * {@code security.lockout.*} configuration as the in-process strategy.
 */
@Component
@Profile("redis")
public class RedisLoginAttemptService implements LoginAttemptService {

    private static final String KEY_PREFIX = "loginfail:";

    /**
     * Atomic INCR + PEXPIRE. Done in one Lua call so the key can never be left without a TTL — a
     * non-atomic INCR-then-EXPIRE could, on a crash between the two, strand a counter at/above the
     * threshold with no expiry, locking the account permanently (a locked user never calls
     * recordFailure again, so the TTL would never be restored).
     */
    private static final RedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>("""
            local n = redis.call('incr', KEYS[1])
            redis.call('pexpire', KEYS[1], ARGV[1])
            return n
            """, Long.class);

    private final StringRedisTemplate redis;
    private final int maxAttempts;
    private final String windowMillis;

    public RedisLoginAttemptService(StringRedisTemplate redis, SecurityProperties securityProperties) {
        this.redis = redis;
        this.maxAttempts = securityProperties.getLockout().getMaxAttempts();
        this.windowMillis = Long.toString(
                Duration.ofMinutes(securityProperties.getLockout().getWindowMinutes()).toMillis());
    }

    @Override
    public boolean isLockedOut(String username) {
        String value = redis.opsForValue().get(key(username));
        return value != null && Integer.parseInt(value) >= maxAttempts;
    }

    @Override
    public void recordFailure(String username) {
        redis.execute(INCR_WITH_TTL, List.of(key(username)), windowMillis);
    }

    @Override
    public void recordSuccess(String username) {
        redis.delete(key(username));
    }

    private String key(String username) {
        return KEY_PREFIX + username;
    }
}
