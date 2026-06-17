package com.firstclub.membership.service.impl;

import com.firstclub.membership.service.PaymentGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Demo adapter — records the billing intent and returns a synthetic reference without calling a
 * real provider. Production replaces this bean with a Stripe/Razorpay adapter; nothing else changes.
 */
@Service
@Slf4j
public class NoOpPaymentGateway implements PaymentGateway {

    @Override
    public PaymentResult charge(Long userId, BigDecimal amount, String description) {
        String reference = "pay_" + UUID.randomUUID();
        log.info("Charged user {} amount {} ({}) — reference {}", userId, amount, description, reference);
        return new PaymentResult(reference, true);
    }

    @Override
    public PaymentResult refund(String originalReference, BigDecimal amount) {
        String reference = "rfnd_" + UUID.randomUUID();
        log.info("Refunded {} against {} — reference {}", amount, originalReference, reference);
        return new PaymentResult(reference, true);
    }
}
