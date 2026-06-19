package com.firstclub.membership.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning for the payment circuit breaker and retry (Resilience4j). Every value is overridable via
 * {@code payment.resilience.*}; the defaults are production-safe for a typical synchronous PSP.
 */
@ConfigurationProperties(prefix = "payment.resilience")
@Data
public class PaymentResilienceProperties {

    private CircuitBreaker circuitBreaker = new CircuitBreaker();
    private Retry retry = new Retry();

    @Data
    public static class CircuitBreaker {
        /** Trip when this % of calls in the window fail. */
        private float failureRateThreshold = 50f;
        /** Rolling window of calls evaluated for the failure rate. */
        private int slidingWindowSize = 20;
        /** Don't evaluate the rate until at least this many calls are recorded. */
        private int minimumNumberOfCalls = 10;
        /** How long the breaker stays OPEN (fail-fast) before probing again. */
        private Duration waitDurationInOpenState = Duration.ofSeconds(30);
        /** Probe calls allowed while HALF_OPEN to decide recovery. */
        private int permittedCallsInHalfOpenState = 5;
        /** A call slower than this counts as a (slow-call) failure. */
        private Duration slowCallDurationThreshold = Duration.ofSeconds(8);
        private float slowCallRateThreshold = 100f;
    }

    @Data
    public static class Retry {
        /** Total attempts including the first (so 3 = 1 try + 2 retries). */
        private int maxAttempts = 3;
        /** Base backoff before the first retry. */
        private Duration waitDuration = Duration.ofMillis(200);
        /** Exponential backoff multiplier between retries. */
        private double backoffMultiplier = 2.0;
    }
}
