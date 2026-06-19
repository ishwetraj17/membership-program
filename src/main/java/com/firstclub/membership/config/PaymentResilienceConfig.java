package com.firstclub.membership.config;

import com.firstclub.membership.service.PaymentTransientException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the payment {@code CircuitBreaker} and {@code Retry} registries from
 * {@link PaymentResilienceProperties} and exposes their Micrometer metrics.
 *
 * <p>Wired programmatically (rather than via Resilience4j's annotation auto-config) so the protected
 * behaviour is explicit and unit-testable, and so only {@link PaymentTransientException} — an
 * unknown-outcome transport failure — drives retries and trips the breaker. Business declines never
 * do: they come back as a {@code FAILED} result, not an exception.
 */
@Configuration
public class PaymentResilienceConfig {

    /** The single named instance both registries expose; {@link com.firstclub.membership.service.impl.ResilientPaymentGateway} looks it up. */
    public static final String INSTANCE = "payment";

    @Bean
    public CircuitBreakerRegistry paymentCircuitBreakerRegistry(PaymentResilienceProperties props) {
        PaymentResilienceProperties.CircuitBreaker cb = props.getCircuitBreaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(cb.getFailureRateThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cb.getSlidingWindowSize())
                .minimumNumberOfCalls(cb.getMinimumNumberOfCalls())
                .waitDurationInOpenState(cb.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(cb.getPermittedCallsInHalfOpenState())
                .slowCallDurationThreshold(cb.getSlowCallDurationThreshold())
                .slowCallRateThreshold(cb.getSlowCallRateThreshold())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // Only transport failures count toward opening; declines are normal results.
                .recordExceptions(PaymentTransientException.class)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        registry.circuitBreaker(INSTANCE); // pre-create so its metrics exist from startup
        return registry;
    }

    @Bean
    public RetryRegistry paymentRetryRegistry(PaymentResilienceProperties props) {
        PaymentResilienceProperties.Retry r = props.getRetry();
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(r.getMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(
                        r.getWaitDuration(), r.getBackoffMultiplier()))
                // Retry ONLY unknown-outcome transport failures; never a decline or a bug.
                .retryExceptions(PaymentTransientException.class)
                .build();
        RetryRegistry registry = RetryRegistry.of(config);
        registry.retry(INSTANCE);
        return registry;
    }

    /** Exposes resilience4j_circuitbreaker_* meters (state, calls) — auto-bound by Spring Boot. */
    @Bean
    public MeterBinder paymentCircuitBreakerMetrics(CircuitBreakerRegistry registry) {
        return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry);
    }

    /** Exposes resilience4j_retry_* meters (calls by kind: successful/failed with/without retry). */
    @Bean
    public MeterBinder paymentRetryMetrics(RetryRegistry registry) {
        return TaggedRetryMetrics.ofRetryRegistry(registry);
    }
}
