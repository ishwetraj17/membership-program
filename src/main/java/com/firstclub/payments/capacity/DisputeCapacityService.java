package com.firstclub.payments.capacity;

import com.firstclub.payments.entity.Payment;

import java.math.BigDecimal;

/**
 * Domain service that owns all capacity-checking logic for dispute mutations.
 *
 * <p>Must be called <em>after</em> the {@link Payment} row has been loaded
 * with a pessimistic write lock (SELECT FOR UPDATE).
 */
public interface DisputeCapacityService {

    /**
     * Asserts that {@code disputeAmount} does not exceed the capacity still
     * available to be placed under dispute on {@code payment}.
     *
     * <pre>disputable = capturedAmount − refundedAmount − disputedAmount</pre>
     *
     * @throws com.firstclub.membership.exception.MembershipException with code
     *         {@code DISPUTE_AMOUNT_EXCEEDS_LIMIT} (HTTP 422) if over-limit
     */
    void checkDisputeCapacity(Payment payment, BigDecimal disputeAmount);
}
