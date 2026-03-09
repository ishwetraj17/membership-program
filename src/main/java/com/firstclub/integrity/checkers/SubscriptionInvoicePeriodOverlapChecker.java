package com.firstclub.integrity.checkers;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.integrity.InvariantChecker;
import com.firstclub.integrity.InvariantResult;
import com.firstclub.integrity.InvariantSeverity;
import com.firstclub.integrity.InvariantViolation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Detects invoices within the same subscription whose billing periods overlap.
 *
 * <p>Each subscription should have non-overlapping, contiguous invoice periods.
 * An overlap means a customer was billed twice for the same calendar days,
 * or that a proration was recorded with incorrect period boundaries.
 */
@Component
@RequiredArgsConstructor
public class SubscriptionInvoicePeriodOverlapChecker implements InvariantChecker {

    public static final String NAME = "SUBSCRIPTION_INVOICE_PERIOD_OVERLAP";
    private static final String REPAIR =
            "For each pair of overlapping invoices: examine their periodStart/periodEnd values and "
            + "determine which invoice has the incorrect period. Issue a credit note for the "
            + "overlapping amount and correct the period boundaries. Trigger a re-sync of the "
            + "revenue recognition schedule for the subscription.";

    private final InvoiceRepository invoiceRepository;

    @Override public String getName()               { return NAME; }
    @Override public InvariantSeverity getSeverity() { return InvariantSeverity.MEDIUM; }

    @Override
    public InvariantResult check() {
        List<Long> subscriptionIds = invoiceRepository.findDistinctSubscriptionIds();

        List<InvariantViolation> violations = new ArrayList<>();
        for (Long subscriptionId : subscriptionIds) {
            List<Invoice> invoices = invoiceRepository.findBySubscriptionId(subscriptionId)
                    .stream()
                    .filter(inv -> inv.getPeriodStart() != null && inv.getPeriodEnd() != null)
                    .sorted(Comparator.comparing(Invoice::getPeriodStart))
                    .toList();

            for (int i = 1; i < invoices.size(); i++) {
                Invoice prev = invoices.get(i - 1);
                Invoice curr = invoices.get(i);

                // Overlap: previous period ends AFTER the current period starts
                if (prev.getPeriodEnd().isAfter(curr.getPeriodStart())) {
                    violations.add(InvariantViolation.builder()
                            .entityType("Invoice")
                            .entityId(String.valueOf(curr.getId()))
                            .description(String.format(
                                    "Subscription %d has overlapping invoice periods: "
                                    + "invoice %d [%s → %s] overlaps with invoice %d [%s → %s]",
                                    subscriptionId,
                                    prev.getId(), prev.getPeriodStart(), prev.getPeriodEnd(),
                                    curr.getId(), curr.getPeriodStart(), curr.getPeriodEnd()))
                            .suggestedRepairAction(REPAIR)
                            .build());
                }
            }
        }

        return violations.isEmpty()
                ? InvariantResult.pass(NAME, getSeverity())
                : InvariantResult.fail(NAME, getSeverity(), violations);
    }
}
