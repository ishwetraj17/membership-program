package com.firstclub.membership.service.impl;

import com.firstclub.membership.service.ChargeRequest;
import com.firstclub.membership.service.PaymentGateway;
import com.firstclub.membership.service.RefundRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Demo adapter — records the billing intent and returns a synthetic reference without calling a
 * real provider. Production replaces this bean with a PSP adapter carrying the same
 * {@code "paymentProvider"} qualifier; {@link ResilientPaymentGateway} and
 * {@link MeteredPaymentGateway} then wrap it transparently (resilience + metrics), and the adapter
 * consumes the timeouts/client wired in {@code PaymentClientFactory}.
 *
 * <p>It honours the contract a real adapter must: the returned {@code reference} is derived from the
 * caller's {@link ChargeRequest#idempotencyKey()}, so replaying the same key yields the same
 * reference — modelling the dedupe a production PSP guarantees.
 */
@Service
@Qualifier("paymentProvider")
@Slf4j
public class NoOpPaymentGateway implements PaymentGateway {

    @Override
    public PaymentResult charge(ChargeRequest request) {
        String reference = "pay_" + deterministic(request.idempotencyKey());
        log.info("Charged customer {} amount {} {} ({}) — key={} corr={} reference={}",
                request.customerReference(), request.amount(), request.currency(),
                request.description(), request.idempotencyKey(), request.correlationId(), reference);
        return PaymentResult.succeeded(reference);
    }

    @Override
    public PaymentResult refund(RefundRequest request) {
        String reference = "rfnd_" + deterministic(request.idempotencyKey());
        log.info("Refunded {} against {} — key={} corr={} reference={}",
                request.amount(), request.originalReference(), request.idempotencyKey(),
                request.correlationId(), reference);
        return PaymentResult.succeeded(reference);
    }

    /** Stable pseudo-reference from the idempotency key (random only when no key is supplied). */
    private String deterministic(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return UUID.nameUUIDFromBytes(idempotencyKey.getBytes()).toString();
    }
}
