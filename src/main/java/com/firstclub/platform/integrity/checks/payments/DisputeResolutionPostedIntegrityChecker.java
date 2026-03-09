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
 * Phase 15 — Verifies that every WON or LOST dispute has
 * {@code resolutionPosted == true}.
 *
 * <p>A resolved dispute without a posted resolution means the chargeback outcome
 * was recorded in the disputes table but the corresponding ledger reversal
 * (DR PG_CLEARING / CR DISPUTE_RESERVE for WON, or DR CHARGEBACK_EXPENSE /
 * CR DISPUTE_RESERVE for LOST) was never posted.  This leaves the ledger and
 * the disputes table in an inconsistent state.
 */
@Component
@RequiredArgsConstructor
public class DisputeResolutionPostedIntegrityChecker implements IntegrityChecker {

    private static final int PREVIEW_CAP = 50;

    private static final List<DisputeStatus> TERMINAL_STATUSES =
            List.of(DisputeStatus.WON, DisputeStatus.LOST);

    private final DisputeRepository disputeRepository;

    @Override
    public String getInvariantKey() {
        return "payments.dispute_resolution_posted";
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
                        .filter(d -> TERMINAL_STATUSES.contains(d.getStatus()))
                        .collect(Collectors.toList())
                : disputeRepository.findAll().stream()
                        .filter(d -> TERMINAL_STATUSES.contains(d.getStatus()))
                        .collect(Collectors.toList());

        List<IntegrityViolation> violations = new ArrayList<>();

        for (Dispute dispute : disputes) {
            if (!dispute.isResolutionPosted()) {
                violations.add(IntegrityViolation.builder()
                        .entityType("DISPUTE")
                        .entityId(dispute.getId())
                        .details("Dispute status=" + dispute.getStatus()
                                + " but resolutionPosted=false — resolution accounting entry missing")
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
                        ? "All resolved disputes have resolutionPosted=true"
                        : violations.size() + " resolved disputes are missing their resolution accounting entry")
                .suggestedRepairKey(passed ? null : "payments.post_missing_dispute_resolution")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
