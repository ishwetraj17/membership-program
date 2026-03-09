package com.firstclub.platform.integrity.checks.payments;

import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.refund.entity.RefundV2Status;
import com.firstclub.payments.refund.repository.RefundV2Repository;
import com.firstclub.payments.repository.PaymentRepository;
import com.firstclub.platform.integrity.IntegrityCheckResult;
import com.firstclub.platform.integrity.IntegrityCheckSeverity;
import com.firstclub.platform.integrity.IntegrityChecker;
import com.firstclub.platform.integrity.IntegrityViolation;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Verifies that the cumulative sum of COMPLETED refunds for a payment does not
 * exceed the payment's captured amount.
 *
 * <p>This is a financial integrity invariant: a payment cannot be refunded more
 * than it was captured for.
 */
@Component
@RequiredArgsConstructor
public class RefundWithinRefundableAmountChecker implements IntegrityChecker {

    private static final int LOOK_BACK_DAYS = 90;
    private static final int PREVIEW_CAP = 50;

    private final PaymentRepository  paymentRepository;
    private final RefundV2Repository refundV2Repository;

    @Override
    public String getInvariantKey() {
        return "payments.refund_within_refundable_amount";
    }

    @Override
    public IntegrityCheckSeverity getSeverity() {
        return IntegrityCheckSeverity.CRITICAL;
    }

    @Override
    @Transactional(readOnly = true)
    public IntegrityCheckResult run(@Nullable Long merchantId) {
        var payments = merchantId != null
                ? paymentRepository.findByMerchantId(merchantId)
                : paymentRepository.findByStatusAndCapturedAtBetween(
                        com.firstclub.payments.entity.PaymentStatus.CAPTURED,
                        LocalDateTime.now().minusDays(LOOK_BACK_DAYS), LocalDateTime.now());

        List<IntegrityViolation> violations = new ArrayList<>();

        for (Payment payment : payments) {
            if (payment.getCapturedAmount() == null) continue;

            BigDecimal completedRefunds = refundV2Repository
                    .sumAmountByPaymentIdAndStatus(payment.getId(), RefundV2Status.COMPLETED);
            if (completedRefunds == null) completedRefunds = BigDecimal.ZERO;

            if (completedRefunds.compareTo(payment.getCapturedAmount()) > 0) {
                violations.add(IntegrityViolation.builder()
                        .entityType("PAYMENT")
                        .entityId(payment.getId())
                        .details("completedRefunds=" + completedRefunds
                                + " exceeds capturedAmount=" + payment.getCapturedAmount()
                                + " (over-refunded=" + completedRefunds.subtract(payment.getCapturedAmount()) + ")")
                        .preview("gatewayTxnId=" + payment.getGatewayTxnId()
                                + ", currency=" + payment.getCurrency())
                        .build());
            }
        }

        boolean passed = violations.isEmpty();
        return IntegrityCheckResult.builder()
                .invariantKey(getInvariantKey())
                .severity(getSeverity())
                .passed(passed)
                .violationCount(violations.size())
                .violations(violations.stream().limit(PREVIEW_CAP).collect(Collectors.toList()))
                .details(passed
                        ? "All payments have refunded amount within captured amount"
                        : violations.size() + " payments have cumulative refunds exceeding capture")
                .suggestedRepairKey(passed ? null : "payments.reverse_excess_refund")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
