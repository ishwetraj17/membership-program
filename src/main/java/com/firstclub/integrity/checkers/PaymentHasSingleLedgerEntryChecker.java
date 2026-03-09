package com.firstclub.integrity.checkers;

import com.firstclub.integrity.InvariantChecker;
import com.firstclub.integrity.InvariantResult;
import com.firstclub.integrity.InvariantSeverity;
import com.firstclub.integrity.InvariantViolation;
import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerReferenceType;
import com.firstclub.ledger.repository.LedgerEntryRepository;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies that every CAPTURED payment has exactly one
 * {@code PAYMENT_CAPTURED} ledger entry.
 *
 * <ul>
 *   <li>0 entries → payment was captured at the gateway but never posted to the
 *       ledger (financial gap).</li>
 *   <li>2+ entries → the capture event was posted more than once (double-count).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PaymentHasSingleLedgerEntryChecker implements InvariantChecker {

    public static final String NAME = "PAYMENT_HAS_SINGLE_LEDGER_ENTRY";
    private static final String REPAIR =
            "For 0-entry gaps: manually post a PAYMENT_CAPTURED ledger entry or trigger "
            + "PaymentSucceededHandler. For duplicates: use LedgerReversalService to void the extra entry.";

    private final PaymentRepository      paymentRepository;
    private final LedgerEntryRepository  ledgerEntryRepository;

    @Override public String getName()            { return NAME; }
    @Override public InvariantSeverity getSeverity() { return InvariantSeverity.HIGH; }

    @Override
    public InvariantResult check() {
        List<Payment> captured = paymentRepository.findByStatus(PaymentStatus.CAPTURED);

        List<InvariantViolation> violations = new ArrayList<>();
        for (Payment payment : captured) {
            List<LedgerEntry> entries = ledgerEntryRepository
                    .findByReferenceTypeAndReferenceId(LedgerReferenceType.PAYMENT, payment.getId())
                    .stream()
                    .filter(e -> e.getEntryType() == LedgerEntryType.PAYMENT_CAPTURED)
                    .toList();

            if (entries.size() != 1) {
                violations.add(InvariantViolation.builder()
                        .entityType("Payment")
                        .entityId(String.valueOf(payment.getId()))
                        .description(String.format(
                                "Payment %d has %d PAYMENT_CAPTURED ledger entries (expected exactly 1)",
                                payment.getId(), entries.size()))
                        .suggestedRepairAction(REPAIR)
                        .build());
            }
        }

        return violations.isEmpty()
                ? InvariantResult.pass(NAME, getSeverity())
                : InvariantResult.fail(NAME, getSeverity(), violations);
    }
}
