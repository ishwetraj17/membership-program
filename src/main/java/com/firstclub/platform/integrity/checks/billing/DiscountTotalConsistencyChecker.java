package com.firstclub.platform.integrity.checks.billing;

import com.firstclub.billing.entity.InvoiceLine;
import com.firstclub.billing.entity.InvoiceLineType;
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
 * Verifies that {@code invoice.discount_total} equals the sum of all
 * {@link InvoiceLineType#DISCOUNT} lines on that invoice.
 */
@Component
@RequiredArgsConstructor
public class DiscountTotalConsistencyChecker implements IntegrityChecker {

    private static final int LOOK_BACK_DAYS = 90;
    private static final int PREVIEW_CAP = 50;

    private final InvoiceRepository    invoiceRepository;
    private final InvoiceLineRepository invoiceLineRepository;

    @Override
    public String getInvariantKey() {
        return "billing.discount_total_consistent";
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

        List<IntegrityViolation> violations = new ArrayList<>();

        for (var invoice : invoices) {
            BigDecimal storedDiscount = invoice.getDiscountTotal() != null
                    ? invoice.getDiscountTotal() : BigDecimal.ZERO;

            List<InvoiceLine> discountLines = invoiceLineRepository
                    .findByInvoiceId(invoice.getId())
                    .stream()
                    .filter(l -> InvoiceLineType.DISCOUNT.equals(l.getLineType()))
                    .collect(Collectors.toList());

            // Discount lines typically store negative amounts; take abs sum
            BigDecimal lineDiscountSum = discountLines.stream()
                    .map(InvoiceLine::getAmount)
                    .map(BigDecimal::abs)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // stored discountTotal should equal abs sum of DISCOUNT lines
            if (lineDiscountSum.compareTo(storedDiscount) != 0) {
                violations.add(IntegrityViolation.builder()
                        .entityType("INVOICE")
                        .entityId(invoice.getId())
                        .details("discountTotal=" + storedDiscount
                                + " but discountLineSum=" + lineDiscountSum)
                        .preview("invoiceNumber=" + invoice.getInvoiceNumber())
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
                        ? "All " + invoices.size() + " invoices have consistent discount totals"
                        : violations.size() + " invoices have discount_total ≠ DISCOUNT line sum")
                .suggestedRepairKey(passed ? null : "billing.recalculate_discount_total")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
