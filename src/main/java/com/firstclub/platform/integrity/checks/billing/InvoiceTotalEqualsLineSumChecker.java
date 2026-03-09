package com.firstclub.platform.integrity.checks.billing;

import com.firstclub.billing.entity.InvoiceLine;
import com.firstclub.billing.repository.InvoiceLineRepository;
import com.firstclub.billing.repository.InvoiceRepository;
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
 * Verifies that every invoice's {@code grand_total} equals the arithmetic sum
 * of its {@link InvoiceLine} amounts (positive for charges, negative for credits).
 *
 * <p>Scope: checks invoices created in the last 90 days to avoid full-table scans.
 * For historical audits use the merchant-scoped invocation.
 */
@Component
@RequiredArgsConstructor
public class InvoiceTotalEqualsLineSumChecker implements IntegrityChecker {

    private static final int LOOK_BACK_DAYS = 90;
    private static final int VIOLATION_PREVIEW_CAP = 50;

    private final InvoiceRepository    invoiceRepository;
    private final InvoiceLineRepository invoiceLineRepository;

    @Override
    public String getInvariantKey() {
        return "billing.invoice_total_equals_line_sum";
    }

    @Override
    public IntegrityCheckSeverity getSeverity() {
        return IntegrityCheckSeverity.CRITICAL;
    }

    @Override
    @Transactional(readOnly = true)
    public IntegrityCheckResult run(@Nullable Long merchantId) {
        var invoices = merchantId != null
                ? invoiceRepository.findByMerchantId(merchantId)
                : invoiceRepository.findByCreatedAtBetween(
                        LocalDateTime.now().minusDays(LOOK_BACK_DAYS), LocalDateTime.now());

        List<IntegrityViolation> violations = new ArrayList<>();

        for (var invoice : invoices) {
            if (invoice.getGrandTotal() == null) continue;
            List<InvoiceLine> lines = invoiceLineRepository.findByInvoiceId(invoice.getId());
            BigDecimal lineSum = lines.stream()
                    .map(InvoiceLine::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (lineSum.compareTo(invoice.getGrandTotal()) != 0) {
                violations.add(IntegrityViolation.builder()
                        .entityType("INVOICE")
                        .entityId(invoice.getId())
                        .details("grandTotal=" + invoice.getGrandTotal()
                                + " but lineSum=" + lineSum
                                + " (delta=" + invoice.getGrandTotal().subtract(lineSum) + ")")
                        .preview("invoiceNumber=" + invoice.getInvoiceNumber()
                                + ", status=" + invoice.getStatus())
                        .build());
            }
        }

        return buildResult(violations, invoices.size());
    }

    private IntegrityCheckResult buildResult(List<IntegrityViolation> violations, int checked) {
        boolean passed = violations.isEmpty();
        return IntegrityCheckResult.builder()
                .invariantKey(getInvariantKey())
                .severity(getSeverity())
                .passed(passed)
                .violationCount(violations.size())
                .violations(violations.stream().limit(VIOLATION_PREVIEW_CAP).collect(Collectors.toList()))
                .details(passed
                        ? "All " + checked + " invoices have consistent grand totals"
                        : violations.size() + " of " + checked + " invoices have grand_total ≠ line sum")
                .suggestedRepairKey(passed ? null : "billing.recalculate_invoice_total")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
