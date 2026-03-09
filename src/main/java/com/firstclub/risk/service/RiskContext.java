package com.firstclub.risk.service;

/**
 * Carries the payment context needed by rule evaluators during a single evaluation pass.
 *
 * @param merchantId       Merchant owning the payment intent.
 * @param paymentIntentId  The payment intent being confirmed.
 * @param customerId       Customer initiating the payment (used as velocity key).
 * @param ip               Originating IP address (null when unavailable).
 * @param deviceId         Device fingerprint (null when unavailable).
 */
public record RiskContext(
        Long merchantId,
        Long paymentIntentId,
        Long customerId,
        String ip,
        String deviceId
) {}
