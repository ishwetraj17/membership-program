package com.firstclub.membership.security;

import com.firstclub.membership.exception.MembershipException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory rate limiter for the login endpoint.
 *
 * Tracks failed (and total) login attempts per email address within a
 * sliding window. After {@value #MAX_ATTEMPTS} attempts in
 * {@value #WINDOW_MS} ms the next call throws a 429 exception.
 *
 * A scheduled job evicts stale entries every 15 minutes to prevent
 * unbounded memory growth.
 *
 * NOTE: This implementation is per-node. In a multi-instance deployment
 * replace the ConcurrentHashMap with a distributed cache (e.g. Redis).
 */
@Component
@Slf4j
public class LoginRateLimiterService {

    static final int MAX_ATTEMPTS = 5;
    static final long WINDOW_MS = 15 * 60 * 1000L; // 15 minutes

    private final ConcurrentHashMap<String, LoginAttempt> attempts = new ConcurrentHashMap<>();

    /**
     * Record an attempt for {@code identifier} (typically the email) and
     * throw {@link MembershipException} with HTTP 429 if the limit is exceeded.
     */
    public void checkAndRecord(String identifier) {
        LoginAttempt current = attempts.compute(identifier, (key, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null || now - existing.windowStart() > WINDOW_MS) {
                return new LoginAttempt(now, 1);
            }
            return new LoginAttempt(existing.windowStart(), existing.count() + 1);
        });

        if (current.count() > MAX_ATTEMPTS) {
            log.warn("Rate limit exceeded for identifier: {}", identifier);
            throw new MembershipException(
                "Too many login attempts. Please try again in 15 minutes.",
                "RATE_LIMIT_EXCEEDED",
                HttpStatus.TOO_MANY_REQUESTS
            );
        }
    }

    /**
     * Reset the rate-limit counter for {@code identifier} after a successful login,
     * so legitimate users are not locked out for 15 minutes after one bad attempt.
     */
    public void reset(String identifier) {
        attempts.remove(identifier);
    }

    /** Purge entries whose window has expired to prevent unbounded memory growth. */
    @Scheduled(fixedDelay = WINDOW_MS)
    void cleanup() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        int removed = 0;
        var it = attempts.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().windowStart() < cutoff) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("LoginRateLimiter: evicted {} stale entries", removed);
        }
    }

    record LoginAttempt(long windowStart, int count) {}
}
