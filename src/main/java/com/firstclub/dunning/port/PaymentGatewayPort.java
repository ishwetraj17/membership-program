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
     * Rich result returned by {@link #chargeWithCode(Long)}, carrying both the
     * binary outcome and the raw gateway failure code.
     *
     * @param outcome     {@link ChargeOutcome#SUCCESS} or {@link ChargeOutcome#FAILED}
     * @param failureCode raw gateway-specific decline code; {@code null} on success
     */
    record ChargeResult(ChargeOutcome outcome, String failureCode) {

        /** Convenience factory for a successful charge. */
        public static ChargeResult success() {
            return new ChargeResult(ChargeOutcome.SUCCESS, null);
        }

        /** Convenience factory for a failed charge with a known decline code. */
        public static ChargeResult failed(String code) {
            return new ChargeResult(ChargeOutcome.FAILED, code);
        }

        /** {@code true} iff the charge was captured successfully. */
        public boolean isSuccess() {
            return outcome == ChargeOutcome.SUCCESS;
        }
    }

    /**
     * Attempts to charge the payment intent with the given ID.
     *
     * <p>Callers that only care about success/failure may use this method.
     * Callers that need the failure code (e.g. the v2 dunning engine) should
     * prefer {@link #chargeWithCode(Long)}.
     *
     * @param paymentIntentId the ID of an existing PI in REQUIRES_PAYMENT_METHOD state
     * @return {@link ChargeOutcome#SUCCESS} if the charge was captured,
     *         {@link ChargeOutcome#FAILED} otherwise
     */
    ChargeOutcome charge(Long paymentIntentId);

    /**
     * Charge the payment intent and return a rich result that includes the raw
     * gateway failure code when the charge is declined.
     *
     * <p>The default implementation delegates to {@link #charge(Long)} and wraps
     * the binary result with a generic {@code "gateway_declined"} code on failure.
     * Production adapters should override this method to expose the real decline code
     * from the gateway response.
     *
     * @param paymentIntentId the ID of an existing PI in REQUIRES_PAYMENT_METHOD state
     * @return {@link ChargeResult} containing the outcome and (on failure) the raw code
     */
    default ChargeResult chargeWithCode(Long paymentIntentId) {
        ChargeOutcome outcome = charge(paymentIntentId);
        return outcome == ChargeOutcome.SUCCESS
                ? ChargeResult.success()
                : ChargeResult.failed("gateway_declined");
    }
}
