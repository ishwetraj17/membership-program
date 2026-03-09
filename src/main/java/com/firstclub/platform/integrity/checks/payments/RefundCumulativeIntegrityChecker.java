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
 * Phase 15 — Checks that {@code payments.refunded_amount} equals the sum of
 * all COMPLETED {@code refunds_v2} rows for the same payment.
 *
 * <p>A mismatch indicates that a refund was persisted without updating the
 * payment counter (or vice-versa), which will cause the next over-refund check
 * to produce incorrect results.
 */
@Component
@RequiredArgsConstructor
public class RefundCumulativeIntegrityChecker implements IntegrityChecker {

    private static final int LOOK_BACK_DAYS = 90;
    private static final int PREVIEW_CAP    = 50;

    private final PaymentRepository  paymentRepository;
    private final RefundV2Repository refundV2Repository;

    @Override
    public String getInvariantKey() {
        return "payments.refund_cumulative_consistency";
    }

    @Override
    public IntegrityCheckSeverity getSeverity() {
        return IntegrityCheckSeverity.CRITICAL;
    }

    @Override
    @Transactional(readOnly = true)
    public IntegrityCheckResult run(@Nullable Long merchantId) {
        List<Payment> payments = merchantId != null
                ? paymentRepository.findByMerchantId(merchantId)
                : paymentRepository.findByStatusAndCapturedAtBetween(
                        com.firstclub.payments.entity.PaymentStatus.CAPTURED,
                        LocalDateTime.now().minusDays(LOOK_BACK_DAYS),
                        LocalDateTime.now());

        List<IntegrityViolation> violations = new ArrayList<>();

        for (Payment payment : payments) {
            if (payment.getRefundedAmount() == null) continue;

            BigDecimal sumCompleted = refundV2Repository
                    .sumAmountByPaymentIdAndStatus(payment.getId(), RefundV2Status.COMPLETED);
            if (sumCompleted == null) sumCompleted = BigDecimal.ZERO;

            if (sumCompleted.compareTo(payment.getRefundedAmount()) != 0) {
                violations.add(IntegrityViolation.builder()
                        .entityType("PAYMENT")
                        .entityId(payment.getId())
                        .details("payment.refundedAmount=" + payment.getRefundedAmount()
                                + " but SUM(COMPLETED refunds)=" + sumCompleted
                                + " (delta=" + sumCompleted.subtract(payment.getRefundedAmount()) + ")")
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
                        ? "payment.refunded_amount matches SUM(COMPLETED refunds_v2) for all sampled payments"
                        : violations.size() + " payments have a mismatch between refunded_amount and completed refund sum")
                .suggestedRepairKey(passed ? null : "payments.reconcile_refunded_amount")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
