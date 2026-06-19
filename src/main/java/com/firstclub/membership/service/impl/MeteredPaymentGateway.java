package com.firstclub.membership.service.impl;

import com.firstclub.membership.service.ChargeRequest;
import com.firstclub.membership.service.PaymentGateway;
import com.firstclub.membership.service.RefundRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * Outermost decorator in the payment stack: {@code Metered → Resilient → PSP adapter}. Marked
 * {@link Primary} so all callers inject it transparently; it delegates to the resilience-wrapped
 * gateway and records the final outcome and latency.
 *
 * <p>Emits {@code membership.payment{operation=charge|refund, result=success|failure|pending}} as a
 * {@link Timer} — giving payment success/failure counts <i>and</i> latency (including retry/backoff
 * time, i.e. the member-perceived duration) — without putting any metrics code in the subscription
 * business logic. Retry counts and circuit-breaker state come from the Resilience4j Micrometer
 * binders (see {@code PaymentResilienceConfig}).
 */
@Service
@Primary
@Slf4j
public class MeteredPaymentGateway implements PaymentGateway {

    private final PaymentGateway delegate;
    private final MeterRegistry meterRegistry;

    public MeteredPaymentGateway(@Qualifier("resilientPaymentGateway") PaymentGateway delegate,
                                 MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public PaymentResult charge(ChargeRequest request) {
        return metered("charge", () -> delegate.charge(request));
    }

    @Override
    public PaymentResult refund(RefundRequest request) {
        return metered("refund", () -> delegate.refund(request));
    }

    private PaymentResult metered(String operation, Supplier<PaymentResult> call) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "failure";
        try {
            PaymentResult result = call.get();
            outcome = switch (result.status()) {
                case SUCCEEDED -> "success";
                case PENDING -> "pending";
                case FAILED -> "failure";
            };
            return result;
        } catch (RuntimeException e) {
            // A thrown failure (e.g. transport error or open circuit) counts as a failure too.
            outcome = "failure";
            throw e;
        } finally {
            sample.stop(meterRegistry.timer("membership.payment", "operation", operation, "result", outcome));
        }
    }
}
