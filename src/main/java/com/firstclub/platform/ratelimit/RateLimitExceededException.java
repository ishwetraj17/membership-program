package com.firstclub.platform.ratelimit;

import java.time.Instant;

/**
 * Thrown when a rate limit policy is exceeded.
 *
 * <p>The {@link com.firstclub.membership.exception.GlobalExceptionHandler}
 * catches this and returns HTTP 429 with appropriate rate-limit response headers.
 */
public class RateLimitExceededException extends RuntimeException {

    private final RateLimitPolicy policy;
    private final String          subject;
    private final Instant         resetAt;

    public RateLimitExceededException(RateLimitPolicy policy, String subject, Instant resetAt) {
        super(String.format(
                "Rate limit exceeded for policy %s (subject: %s). Retry after %s.",
                policy.name(), subject, resetAt));
        this.policy  = policy;
        this.subject = subject;
        this.resetAt = resetAt;
    }

    public RateLimitPolicy getPolicy()  { return policy; }
    public String          getSubject() { return subject; }
    public Instant         getResetAt() { return resetAt; }

    /** Unix epoch seconds — used for the {@code Retry-After} and
     *  {@code X-RateLimit-Reset} response headers. */
    public long resetEpochSeconds() { return resetAt.getEpochSecond(); }

    /** Human-readable error code for API consumers. */
    public String errorCode() {
        return "RATE_LIMIT_EXCEEDED_" + policy.name();
    }
}
