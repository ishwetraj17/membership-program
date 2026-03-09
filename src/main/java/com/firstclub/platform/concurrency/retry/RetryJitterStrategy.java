package com.firstclub.platform.concurrency.retry;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Computes jitter to add to a base delay, preventing thundering-herd scenarios
 * where all contending threads retry at exactly the same moment.
 *
 * <p>The jitter value is drawn from a uniform distribution in
 * {@code [0, jitterFraction × baseDelayMs]}, so the effective delay after
 * {@link #addJitter} is always ≥ {@code baseDelayMs}.
 */
@Component
public class RetryJitterStrategy {

    /**
     * Returns {@code baseDelayMs} plus a uniformly random jitter component.
     *
     * @param baseDelayMs    deterministic part of the delay (milliseconds); returns 0 if ≤ 0
     * @param jitterFraction fraction of {@code baseDelayMs} that can be added randomly;
     *                       values outside {@code [0, 1]} are clamped
     * @return total sleep duration in milliseconds (≥ {@code baseDelayMs})
     */
    public long addJitter(long baseDelayMs, double jitterFraction) {
        if (baseDelayMs <= 0 || jitterFraction <= 0.0) {
            return Math.max(0, baseDelayMs);
        }
        double clampedFraction = Math.min(1.0, jitterFraction);
        double jitter = ThreadLocalRandom.current().nextDouble() * clampedFraction * baseDelayMs;
        return baseDelayMs + (long) jitter;
    }
}
