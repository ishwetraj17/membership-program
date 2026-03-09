package com.firstclub.platform.integrity.checks.payments;

import com.firstclub.payments.disputes.entity.Dispute;
import com.firstclub.payments.disputes.entity.DisputeStatus;
import com.firstclub.payments.disputes.repository.DisputeRepository;
import com.firstclub.platform.integrity.IntegrityCheckResult;
import com.firstclub.platform.integrity.IntegrityCheckSeverity;
import com.firstclub.platform.integrity.IntegrityChecker;
import com.firstclub.platform.integrity.IntegrityViolation;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Phase 15 — Verifies that every OPEN or UNDER_REVIEW dispute has
 * {@code reservePosted == true}.
 *
 * <p>A dispute without a posted reserve means the DISPUTE_RESERVE account was
 * debited but the flag was never set, or that the accounting call failed after
 * the dispute row was persisted.  Either case requires a repair run.
 */
@Component
@RequiredArgsConstructor
public class DisputeReservePostedIntegrityChecker implements IntegrityChecker {

    private static final int PREVIEW_CAP = 50;

    private static final List<DisputeStatus> ACTIVE_STATUSES =
            List.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW);

    private final DisputeRepository disputeRepository;

    @Override
    public String getInvariantKey() {
        return "payments.dispute_reserve_posted";
    }

    @Override
    public IntegrityCheckSeverity getSeverity() {
        return IntegrityCheckSeverity.CRITICAL;
    }

    @Override
    @Transactional(readOnly = true)
    public IntegrityCheckResult run(@Nullable Long merchantId) {
        List<Dispute> disputes = merchantId != null
                ? disputeRepository.findByMerchantId(merchantId).stream()
                        .filter(d -> ACTIVE_STATUSES.contains(d.getStatus()))
                        .collect(Collectors.toList())
                : disputeRepository.findAll().stream()
                        .filter(d -> ACTIVE_STATUSES.contains(d.getStatus()))
                        .collect(Collectors.toList());

        List<IntegrityViolation> violations = new ArrayList<>();

        for (Dispute dispute : disputes) {
            if (!dispute.isReservePosted()) {
                violations.add(IntegrityViolation.builder()
                        .entityType("DISPUTE")
                        .entityId(dispute.getId())
                        .details("Dispute status=" + dispute.getStatus()
                                + " but reservePosted=false — DISPUTE_RESERVE entry missing")
                        .preview("paymentId=" + dispute.getPaymentId()
                                + ", merchantId=" + dispute.getMerchantId()
                                + ", amount=" + dispute.getAmount())
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
                        ? "All active disputes have reservePosted=true"
                        : violations.size() + " active disputes are missing their DISPUTE_RESERVE accounting entry")
                .suggestedRepairKey(passed ? null : "payments.post_missing_dispute_reserve")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
