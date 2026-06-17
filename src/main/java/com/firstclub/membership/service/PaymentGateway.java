package com.firstclub.membership.service;

import java.math.BigDecimal;

/**
 * Port for charging members. Membership owns billing intent (amounts, timing); the actual
 * money movement belongs to a payment provider behind this interface — swap the adapter to
 * integrate Stripe/Razorpay/etc. without touching subscription logic.
 */
public interface PaymentGateway {

    PaymentResult charge(Long userId, BigDecimal amount, String description);

    PaymentResult refund(String originalReference, BigDecimal amount);

    record PaymentResult(String reference, boolean success) {}
}
