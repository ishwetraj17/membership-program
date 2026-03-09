package com.firstclub.payments.capacity;

import com.firstclub.membership.exception.MembershipException;
import com.firstclub.payments.entity.Payment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Default implementation of {@link RefundCapacityService}.
 *
 * <p>All capacity checks are performed on already-locked {@link Payment} rows
 * (pessimistic write lock acquired by the calling service layer).  This service
 * does <em>not</em> acquire any locks itself — it is purely a guard/validator.
 */
@Service
public class RefundCapacityServiceImpl implements RefundCapacityService {

    @Override
    public BigDecimal computeRefundableAmount(Payment payment) {
        BigDecimal refundable = payment.getCapturedAmount()
                .subtract(payment.getRefundedAmount())
                .subtract(payment.getDisputedAmount());
        // Guard against any rounding that could produce a tiny negative value
        return refundable.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : refundable;
    }

    @Override
    public void checkRefundCapacity(Payment payment, BigDecimal requestedAmount) {
        BigDecimal refundable = computeRefundableAmount(payment);
        if (requestedAmount.compareTo(refundable) > 0) {
            throw new MembershipException(
                    "Refund amount " + requestedAmount
                    + " exceeds refundable amount " + refundable
                    + " for payment " + payment.getId(),
                    "OVER_REFUND",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
}
