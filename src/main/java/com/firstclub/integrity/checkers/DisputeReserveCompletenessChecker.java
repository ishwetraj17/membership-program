package com.firstclub.integrity.checkers;

import com.firstclub.integrity.InvariantChecker;
import com.firstclub.integrity.InvariantResult;
import com.firstclub.integrity.InvariantSeverity;
import com.firstclub.integrity.InvariantViolation;
import com.firstclub.payments.disputes.entity.Dispute;
import com.firstclub.payments.disputes.entity.DisputeStatus;
import com.firstclub.payments.disputes.repository.DisputeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies that every OPEN or UNDER_REVIEW dispute has {@code reservePosted == true}.
 *
 * <p>A dispute without a posted reserve means either the {@code DISPUTE_RESERVE}
 * accounting journal was never written (service crash after the dispute row was
 * inserted) or was rolled back without marking the flag.  The missing entry leaves
 * the balance sheet understating contingent liabilities.
 */
@Component
@RequiredArgsConstructor
public class DisputeReserveCompletenessChecker implements InvariantChecker {

    public static final String NAME = "DISPUTE_RESERVE_COMPLETENESS";
    private static final List<DisputeStatus> ACTIVE_STATUSES =
            List.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW);
    private static final String REPAIR =
            "For each dispute with reservePosted=false: call DisputeAccountingService.postReserve(disputeId) "
            + "inside a transaction. Confirm the DISPUTE_RESERVE_DEBIT / DISPUTE_RESERVE_CREDIT ledger "
            + "entries are created and set reservePosted=true on the dispute.";

    private final DisputeRepository disputeRepository;

    @Override public String getName()               { return NAME; }
    @Override public InvariantSeverity getSeverity() { return InvariantSeverity.HIGH; }

    @Override
    public InvariantResult check() {
        List<Dispute> activeDisputes = disputeRepository.findAll().stream()
                .filter(d -> ACTIVE_STATUSES.contains(d.getStatus()))
                .toList();

        List<InvariantViolation> violations = new ArrayList<>();
        for (Dispute dispute : activeDisputes) {
            if (!dispute.isReservePosted()) {
                violations.add(InvariantViolation.builder()
                        .entityType("Dispute")
                        .entityId(String.valueOf(dispute.getId()))
                        .description(String.format(
                                "Dispute %s has status=%s but reservePosted=false",
                                dispute.getId(), dispute.getStatus()))
                        .suggestedRepairAction(REPAIR)
                        .build());
            }
        }

        return violations.isEmpty()
                ? InvariantResult.pass(NAME, getSeverity())
                : InvariantResult.fail(NAME, getSeverity(), violations);
    }
}
