package com.firstclub.integrity.checkers;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.integrity.InvariantChecker;
import com.firstclub.integrity.InvariantResult;
import com.firstclub.integrity.InvariantSeverity;
import com.firstclub.integrity.InvariantViolation;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies that every PAID subscription invoice has at least one revenue
 * recognition schedule row.
 *
 * <p>A PAID invoice with no schedule means the revenue will never be recognized — it
 * will remain indefinitely as deferred revenue in SUBSCRIPTION_LIABILITY.
 */
@Component
@RequiredArgsConstructor
public class RecognitionScheduleCompletenessChecker implements InvariantChecker {

    public static final String NAME = "RECOGNITION_SCHEDULE_COMPLETENESS";
    private static final String REPAIR =
            "Call RevenueRecognitionScheduleService.generateScheduleForInvoice(invoiceId) for each "
            + "PAID invoice that is missing a schedule. Check that the invoice has a valid service period "
            + "(periodStart and periodEnd must not be null).";

    private final InvoiceRepository                    invoiceRepository;
    private final RevenueRecognitionScheduleRepository scheduleRepository;

    @Override public String getName()            { return NAME; }
    @Override public InvariantSeverity getSeverity() { return InvariantSeverity.MEDIUM; }

    @Override
    public InvariantResult check() {
        List<Invoice> paidSubscriptionInvoices = invoiceRepository
                .findByStatus(InvoiceStatus.PAID)
                .stream()
                .filter(i -> i.getSubscriptionId() != null)
                .toList();

        List<InvariantViolation> violations = new ArrayList<>();
        for (Invoice invoice : paidSubscriptionInvoices) {
            if (!scheduleRepository.existsByInvoiceId(invoice.getId())) {
                violations.add(InvariantViolation.builder()
                        .entityType("Invoice")
                        .entityId(String.valueOf(invoice.getId()))
                        .description(String.format(
                                "PAID invoice %d (subscription %d) has no revenue recognition schedule",
                                invoice.getId(), invoice.getSubscriptionId()))
                        .suggestedRepairAction(REPAIR)
                        .build());
            }
        }

        return violations.isEmpty()
                ? InvariantResult.pass(NAME, getSeverity())
                : InvariantResult.fail(NAME, getSeverity(), violations);
    }
}
