package com.firstclub.payments.capacity;

import com.firstclub.membership.exception.MembershipException;
import com.firstclub.payments.entity.Payment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Default implementation of {@link DisputeCapacityService}.
 */
@Service
public class DisputeCapacityServiceImpl implements DisputeCapacityService {

    @Override
    public void checkDisputeCapacity(Payment payment, BigDecimal disputeAmount) {
        BigDecimal disputable = payment.getCapturedAmount()
                .subtract(payment.getRefundedAmount())
                .subtract(payment.getDisputedAmount());
        if (disputable.compareTo(BigDecimal.ZERO) < 0) {
            disputable = BigDecimal.ZERO;
        }
        if (disputeAmount.compareTo(disputable) > 0) {
            throw new MembershipException(
                    "Dispute amount " + disputeAmount
                    + " exceeds disputable amount " + disputable
                    + " for payment " + payment.getId(),
                    "DISPUTE_AMOUNT_EXCEEDS_LIMIT",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
}
