package com.firstclub.dunning.port;

/**
 * Abstraction over the payment gateway for scheduled renewal and dunning charges.
 *
 * <p>The default implementation ({@code SimulatedPaymentGateway}) drives
 * the payment-intent state machine synchronously so that the renewal and
 * dunning services can act on the result without waiting for an async webhook.
 *
 * <p>Replace or decorate this bean with a real gateway adapter before
 * deploying to production.
 */
public interface PaymentGatewayPort {

    enum ChargeOutcome { SUCCESS, FAILED }

    /**
     * Attempts to charge the payment intent with the given ID.
     *
     * @param paymentIntentId the ID of an existing PI in REQUIRES_PAYMENT_METHOD state
     * @return {@link ChargeOutcome#SUCCESS} if the charge was captured,
     *         {@link ChargeOutcome#FAILED} otherwise
     */
    ChargeOutcome charge(Long paymentIntentId);
}
