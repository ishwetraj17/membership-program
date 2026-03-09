package com.firstclub.platform.integrity.checks.billing;

import com.firstclub.billing.repository.InvoiceRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Verifies that no two invoices for the same subscription have overlapping
 * billing periods ([period_start, period_end] × [period_start, period_end]).
 *
 * <p>Overlapping periods on the same subscription indicate either a billing
 * engine bug or a manual data fix that created a gap-fill invoice on top of
 * an existing one.
 */
@Component
@RequiredArgsConstructor
public class InvoicePeriodOverlapChecker implements IntegrityChecker {

    private static final int LOOK_BACK_DAYS = 90;
    private static final int PREVIEW_CAP = 50;

    private final InvoiceRepository invoiceRepository;

    @Override
    public String getInvariantKey() {
        return "billing.no_overlapping_invoice_periods";
    }

    @Override
    public IntegrityCheckSeverity getSeverity() {
        return IntegrityCheckSeverity.HIGH;
    }

    @Override
    @Transactional(readOnly = true)
    public IntegrityCheckResult run(@Nullable Long merchantId) {
        var invoices = merchantId != null
                ? invoiceRepository.findByMerchantId(merchantId)
                : invoiceRepository.findByCreatedAtBetween(
                        LocalDateTime.now().minusDays(LOOK_BACK_DAYS), LocalDateTime.now());

        // Group by subscriptionId — only invoices that have both period fields
        Map<Long, List<com.firstclub.billing.entity.Invoice>> bySubscription = new HashMap<>();
        for (var invoice : invoices) {
            if (invoice.getSubscriptionId() == null) continue;
            if (invoice.getPeriodStart() == null || invoice.getPeriodEnd() == null) continue;
            bySubscription
                    .computeIfAbsent(invoice.getSubscriptionId(), k -> new ArrayList<>())
                    .add(invoice);
        }

        List<IntegrityViolation> violations = new ArrayList<>();

        for (var entry : bySubscription.entrySet()) {
            List<com.firstclub.billing.entity.Invoice> subInvoices = entry.getValue();
            subInvoices.sort((a, b) -> a.getPeriodStart().compareTo(b.getPeriodStart()));

            for (int i = 0; i < subInvoices.size() - 1; i++) {
                var a = subInvoices.get(i);
                var b = subInvoices.get(i + 1);
                // Overlap: a.periodStart < b.periodEnd AND b.periodStart < a.periodEnd
                if (a.getPeriodStart().isBefore(b.getPeriodEnd())
                        && b.getPeriodStart().isBefore(a.getPeriodEnd())) {
                    violations.add(IntegrityViolation.builder()
                            .entityType("INVOICE")
                            .entityId(a.getId())
                            .details("Invoice " + a.getId() + " period [" + a.getPeriodStart()
                                    + ", " + a.getPeriodEnd() + "] overlaps with invoice "
                                    + b.getId() + " [" + b.getPeriodStart() + ", " + b.getPeriodEnd()
                                    + "] for subscriptionId=" + entry.getKey())
                            .preview("subscriptionId=" + entry.getKey()
                                    + ", conflictingInvoiceId=" + b.getId())
                            .build());
                }
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
                        ? "No overlapping invoice periods detected"
                        : violations.size() + " invoice period overlaps found across subscriptions")
                .suggestedRepairKey(passed ? null : "billing.void_duplicate_period_invoice")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
