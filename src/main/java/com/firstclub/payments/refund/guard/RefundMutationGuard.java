package com.firstclub.payments.refund.guard;

import com.firstclub.membership.exception.MembershipException;
import com.firstclub.payments.capacity.RefundCapacityService;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Component that serialises the critical section of every refund mutation:
 *
 * <ol>
 *   <li>Acquire a pessimistic write lock (SELECT FOR UPDATE) on the
 *       {@link Payment} row — prevents concurrent mutations from reading
 *       stale aggregate amounts.</li>
 *   <li>Delegate to {@link RefundCapacityService} so the over-refund check
 *       is always evaluated against the locked, committed amounts.</li>
 *   <li>Return the locked {@link Payment} to the caller for further
 *       mutation in the same transaction.</li>
 * </ol>
 *
 * <p><strong>Must be called within an active transaction.</strong>  The
 * pessimistic lock is held until the surrounding transaction commits or
 * rolls back.
 */
@Component
@RequiredArgsConstructor
public class RefundMutationGuard {

    private final PaymentRepository      paymentRepository;
    private final RefundCapacityService  refundCapacityService;

    /**
     * Acquires the payment row lock, asserts capacity, and returns the locked
     * {@link Payment} aggregate ready for amount mutation.
     *
     * @param paymentId        ID of the payment to lock
     * @param requestedAmount  refund amount to validate against remaining capacity
     * @return locked and validated {@link Payment}
     * @throws MembershipException {@code PAYMENT_NOT_FOUND} (404) if payment absent
     * @throws MembershipException {@code OVER_REFUND} (422) if amount exceeds capacity
     */
    public Payment acquireAndCheck(Long paymentId, BigDecimal requestedAmount) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new MembershipException(
                        "Payment not found: " + paymentId,
                        "PAYMENT_NOT_FOUND",
                        HttpStatus.NOT_FOUND));

        refundCapacityService.checkRefundCapacity(payment, requestedAmount);

        return payment;
    }
}
