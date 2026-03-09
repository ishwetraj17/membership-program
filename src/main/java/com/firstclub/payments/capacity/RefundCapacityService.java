package com.firstclub.payments.capacity;

import com.firstclub.payments.entity.Payment;

import java.math.BigDecimal;

/**
 * Domain service that owns all capacity-checking logic for refund mutations.
 *
 * <p>Implementations must be called <em>after</em> the {@link Payment} row has
 * been loaded with a pessimistic write lock (SELECT FOR UPDATE) so that every
 * concurrent refund path serialises through the actual, committed amounts.
 */
public interface RefundCapacityService {

    /**
     * Computes how much more can be refunded from this payment.
     *
     * <pre>refundable = capturedAmount − refundedAmount − disputedAmount</pre>
     *
     * @param payment locked Payment aggregate
     * @return remaining refundable amount (always ≥ 0)
     */
    BigDecimal computeRefundableAmount(Payment payment);

    /**
     * Asserts that {@code requestedAmount} does not exceed the refundable
     * capacity on {@code payment}.
     *
     * @throws com.firstclub.membership.exception.MembershipException with code
     *         {@code OVER_REFUND} (HTTP 422) if the requested amount exceeds capacity
     */
    void checkRefundCapacity(Payment payment, BigDecimal requestedAmount);
}
