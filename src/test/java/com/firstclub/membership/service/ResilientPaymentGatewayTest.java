package com.firstclub.membership.service;

import com.firstclub.membership.config.PaymentResilienceConfig;
import com.firstclub.membership.config.PaymentResilienceProperties;
import com.firstclub.membership.service.impl.ResilientPaymentGateway;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates the resilience layer (Parts 4 & 5): retry of transient failures only, no retry on a
 * decline, circuit-breaker OPEN → HALF_OPEN → recovery, and that retries reuse the same idempotency
 * key so a charge can never happen twice.
 */
@DisplayName("Resilient payment gateway — circuit breaker + retry")
class ResilientPaymentGatewayTest {

    private static final PaymentResilienceConfig CONFIG = new PaymentResilienceConfig();

    private ResilientPaymentGateway gateway(PaymentResilienceProperties props, PaymentGateway delegate) {
        CircuitBreakerRegistry cb = CONFIG.paymentCircuitBreakerRegistry(props);
        RetryRegistry retry = CONFIG.paymentRetryRegistry(props);
        return new ResilientPaymentGateway(delegate, cb, retry);
    }

    private ChargeRequest charge() {
        return ChargeRequest.builder()
                .idempotencyKey("charge:test:1").correlationId("corr-1")
                .customerReference("42").amount(new BigDecimal("499")).currency("INR")
                .description("test").build();
    }

    // ── Part 5: retry strategy ────────────────────────────────────────────────

    @Test @DisplayName("retries a transient failure and then succeeds")
    void retriesTransient() {
        PaymentResilienceProperties props = new PaymentResilienceProperties();
        props.getRetry().setMaxAttempts(3);
        props.getRetry().setWaitDuration(Duration.ofMillis(5));
        props.getCircuitBreaker().setMinimumNumberOfCalls(100); // keep breaker closed for this test

        ScriptedGateway delegate = ScriptedGateway.failTransientTimes(1);
        PaymentGateway.PaymentResult result = gateway(props, delegate).charge(charge());

        assertThat(result.success()).isTrue();
        assertThat(delegate.invocations()).isEqualTo(2); // 1 transient failure + 1 success
    }

    @Test @DisplayName("a decline (FAILED result) is NOT retried")
    void doesNotRetryDecline() {
        PaymentResilienceProperties props = new PaymentResilienceProperties();
        props.getRetry().setMaxAttempts(3);
        props.getCircuitBreaker().setMinimumNumberOfCalls(100);

        ScriptedGateway delegate = ScriptedGateway.alwaysDecline();
        PaymentGateway.PaymentResult result = gateway(props, delegate).charge(charge());

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo(PaymentGateway.PaymentResult.Status.FAILED);
        assertThat(delegate.invocations()).isEqualTo(1); // declines don't throw → no retry
    }

    @Test @DisplayName("every retry reuses the same idempotency key (no double charge)")
    void retriesReuseIdempotencyKey() {
        PaymentResilienceProperties props = new PaymentResilienceProperties();
        props.getRetry().setMaxAttempts(3);
        props.getRetry().setWaitDuration(Duration.ofMillis(5));
        props.getCircuitBreaker().setMinimumNumberOfCalls(100);

        ScriptedGateway delegate = ScriptedGateway.failTransientTimes(2);
        gateway(props, delegate).charge(charge());

        assertThat(delegate.invocations()).isEqualTo(3);
        assertThat(delegate.seenKeys()).containsExactly("charge:test:1", "charge:test:1", "charge:test:1");
    }

    // ── Part 4: circuit breaker ───────────────────────────────────────────────

    @Test @DisplayName("opens after the failure rate is breached and then fails fast")
    void opensAndFailsFast() {
        ScriptedGateway delegate = ScriptedGateway.alwaysTransient();
        ResilientPaymentGateway gw = gateway(tightBreakerNoRetry(), delegate);

        // 4 failing calls fill the window (min calls = 4, 100% failures ≥ 50% threshold) → OPEN.
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> gw.charge(charge())).isInstanceOf(RuntimeException.class);
        }
        assertThat(gw.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int beforeFastFail = delegate.invocations();
        // Next call short-circuits: CallNotPermittedException, delegate untouched.
        assertThatThrownBy(() -> gw.charge(charge())).isInstanceOf(CallNotPermittedException.class);
        assertThat(delegate.invocations()).isEqualTo(beforeFastFail);
    }

    @Test @DisplayName("half-open probes recover the breaker to CLOSED")
    void halfOpenRecovers() {
        ScriptedGateway delegate = ScriptedGateway.alwaysTransient();
        ResilientPaymentGateway gw = gateway(tightBreakerNoRetry(), delegate);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> gw.charge(charge())).isInstanceOf(RuntimeException.class);
        }
        assertThat(gw.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Simulate the open-state wait elapsing, then let the provider recover.
        gw.circuitBreaker().transitionToHalfOpenState();
        delegate.recover();
        // permittedCallsInHalfOpenState = 2 successful probes → CLOSED.
        gw.charge(charge());
        gw.charge(charge());

        assertThat(gw.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private PaymentResilienceProperties tightBreakerNoRetry() {
        PaymentResilienceProperties props = new PaymentResilienceProperties();
        props.getRetry().setMaxAttempts(1); // isolate breaker counting from retries
        props.getCircuitBreaker().setSlidingWindowSize(4);
        props.getCircuitBreaker().setMinimumNumberOfCalls(4);
        props.getCircuitBreaker().setFailureRateThreshold(50f);
        props.getCircuitBreaker().setWaitDurationInOpenState(Duration.ofSeconds(10));
        props.getCircuitBreaker().setPermittedCallsInHalfOpenState(2);
        return props;
    }

    /** Configurable PaymentGateway test double recording invocations and idempotency keys. */
    static class ScriptedGateway implements PaymentGateway {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> keys = new ArrayList<>();
        private int transientToThrow;       // throw transient for the first N calls
        private boolean alwaysTransient;
        private boolean decline;

        static ScriptedGateway failTransientTimes(int n) {
            ScriptedGateway g = new ScriptedGateway(); g.transientToThrow = n; return g;
        }
        static ScriptedGateway alwaysTransient() {
            ScriptedGateway g = new ScriptedGateway(); g.alwaysTransient = true; return g;
        }
        static ScriptedGateway alwaysDecline() {
            ScriptedGateway g = new ScriptedGateway(); g.decline = true; return g;
        }
        void recover() { this.alwaysTransient = false; this.transientToThrow = 0; }

        int invocations() { return calls.get(); }
        List<String> seenKeys() { return keys; }

        @Override public PaymentResult charge(ChargeRequest request) {
            calls.incrementAndGet();
            keys.add(request.idempotencyKey());
            if (alwaysTransient) throw new PaymentTransientException("transient");
            if (transientToThrow > 0) { transientToThrow--; throw new PaymentTransientException("transient"); }
            if (decline) return PaymentResult.failed(null, "insufficient_funds");
            return PaymentResult.succeeded("pay_ok");
        }

        @Override public PaymentResult refund(RefundRequest request) {
            calls.incrementAndGet();
            keys.add(request.idempotencyKey());
            return PaymentResult.succeeded("rfnd_ok");
        }
    }
}
