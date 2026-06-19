package com.firstclub.membership.service.impl;

import com.firstclub.membership.config.PaymentResilienceConfig;
import com.firstclub.membership.service.ChargeRequest;
import com.firstclub.membership.service.PaymentGateway;
import com.firstclub.membership.service.PaymentTransientException;
import com.firstclub.membership.service.RefundRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * Resilience decorator around the real PSP adapter — protects <b>charge</b>, <b>refund</b>, and
 * (since recurring billing flows through the same charge path) <b>recurring billing</b> with a
 * Resilience4j circuit breaker and retry.
 *
 * <p>Decoration order is {@code retry(circuitBreaker(call))}: each retry consults the breaker, so
 * once it is OPEN the call fails fast instead of hammering a struggling provider; after
 * {@code wait-duration-in-open-state} it transitions to HALF_OPEN and a few probe calls decide
 * recovery (CLOSED) or re-open.
 *
 * <p><b>Why retrying can't double-charge:</b> a retry re-invokes the delegate with the <i>same</i>
 * {@link ChargeRequest} — hence the same deterministic idempotency key — so the PSP dedupes it
 * against the original attempt. And only {@link PaymentTransientException} (unknown-outcome
 * transport failure) is retried; a decline returns a {@code FAILED} result without throwing, so it
 * is never retried.
 */
@Service
@Qualifier("resilientPaymentGateway")
@Slf4j
public class ResilientPaymentGateway implements PaymentGateway {

    private final PaymentGateway delegate;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public ResilientPaymentGateway(@Qualifier("paymentProvider") PaymentGateway delegate,
                                   CircuitBreakerRegistry circuitBreakerRegistry,
                                   RetryRegistry retryRegistry) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(PaymentResilienceConfig.INSTANCE);
        this.retry = retryRegistry.retry(PaymentResilienceConfig.INSTANCE);
    }

    @Override
    public PaymentResult charge(ChargeRequest request) {
        return guarded(() -> delegate.charge(request));
    }

    @Override
    public PaymentResult refund(RefundRequest request) {
        return guarded(() -> delegate.refund(request));
    }

    /** The circuit breaker guarding this gateway — exposed for health/observability and tests. */
    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    private PaymentResult guarded(Supplier<PaymentResult> call) {
        // retry(circuitBreaker(call)): the breaker is the inner decorator, so each retry attempt
        // consults it and fails fast while OPEN.
        Supplier<PaymentResult> breakered = CircuitBreaker.decorateSupplier(circuitBreaker, call);
        Supplier<PaymentResult> retried = Retry.decorateSupplier(retry, breakered);
        return retried.get();
    }
}
