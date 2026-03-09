package com.firstclub.platform.concurrency.retry;

/**
 * Immutable configuration for exponential back-off with optional jitter.
 *
 * <p>The delay for retry attempt {@code n} (0-indexed) is:
 * <pre>
 *   delay(n) = addJitter( baseDelayMs × multiplier^n, jitterFraction )
 * </pre>
 *
 * @param maxRetries     maximum number of retry attempts after the initial try
 * @param baseDelayMs    starting delay in milliseconds (used for the first retry)
 * @param multiplier     exponential growth factor applied on each successive retry
 * @param jitterFraction fraction of the base delay to add as random noise; use 0 for no jitter
 */
public record RetryBackoffPolicy(
        int maxRetries,
        long baseDelayMs,
        double multiplier,
        double jitterFraction
) {

    /** Standard policy: up to 5 retries, 50 ms base, doubling, 30% jitter. */
    public static RetryBackoffPolicy defaultPolicy() {
        return new RetryBackoffPolicy(5, 50L, 2.0, 0.3);
    }

    /**
     * Computes the sleep duration (ms) for the given retry attempt.
     *
     * @param attempt 0-indexed retry attempt number (0 = first retry, 1 = second, …)
     * @param jitter  strategy used to add random noise to the base delay
     * @return total milliseconds to sleep before re-executing the operation
     */
    public long computeDelay(int attempt, RetryJitterStrategy jitter) {
        long base = (long) (baseDelayMs * Math.pow(multiplier, attempt));
        return jitter.addJitter(base, jitterFraction);
    }
}
