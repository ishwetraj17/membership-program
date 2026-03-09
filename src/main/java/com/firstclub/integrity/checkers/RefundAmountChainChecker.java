package com.firstclub.integrity.checkers;

import com.firstclub.integrity.InvariantChecker;
import com.firstclub.integrity.InvariantResult;
import com.firstclub.integrity.InvariantSeverity;
import com.firstclub.integrity.InvariantViolation;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Verifies that no payment has issued or disputed more than it captured.
 *
 * <p>The invariant: {@code refundedAmount + disputedAmount ≤ capturedAmount}.
 *
 * <p>This is already protected by a DB-level {@code CHECK} constraint added in
 * Phase 9.  This checker acts as a defence-in-depth application-level assertion
 * and covers data migrated before the constraint was added.
 */
@Component
@RequiredArgsConstructor
public class RefundAmountChainChecker implements InvariantChecker {

    public static final String NAME = "REFUND_AMOUNT_CHAIN";
    private static final String REPAIR =
            "Find payments violating the capacity invariant. "
            + "If refundedAmount exceeds capturedAmount, investigate RefundService for over-refund bugs. "
            + "For disputed amounts, check DisputeReserveService. "
            + "Adjust Payment.capturedAmount or void the excess refund/dispute record.";

    private final PaymentRepository paymentRepository;

    @Override public String getName()            { return NAME; }
    @Override public InvariantSeverity getSeverity() { return InvariantSeverity.CRITICAL; }

    @Override
    public InvariantResult check() {
        List<Payment> violations = paymentRepository.findCapacityViolations();

        if (violations.isEmpty()) {
            return InvariantResult.pass(NAME, getSeverity());
        }

        List<InvariantViolation> ivs = violations.stream()
                .map(p -> InvariantViolation.builder()
                        .entityType("Payment")
                        .entityId(String.valueOf(p.getId()))
                        .description(String.format(
                                "Payment %d: refunded(%s) + disputed(%s) = %s > captured(%s)",
                                p.getId(), p.getRefundedAmount(), p.getDisputedAmount(),
                                p.getRefundedAmount().add(p.getDisputedAmount()),
                                p.getCapturedAmount()))
                        .suggestedRepairAction(REPAIR)
                        .build())
                .toList();
        return InvariantResult.fail(NAME, getSeverity(), ivs);
    }
}
