package com.firstclub.platform.integrity.checks.revenue;

import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
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
 * Verifies that the sum of all revenue recognition schedule amounts for an
 * invoice equals that invoice's grand total.
 *
 * <p>Any divergence (positive or negative) means the spread of deferred
 * revenue does not reconcile back to the billed amount.
 */
@Component
@RequiredArgsConstructor
public class ScheduleTotalEqualsInvoiceAmountChecker implements IntegrityChecker {

    private static final int LOOK_BACK_DAYS = 180;
    private static final int PREVIEW_CAP = 50;

    private final RevenueRecognitionScheduleRepository scheduleRepository;
    private final InvoiceRepository invoiceRepository;

    @Override
    public String getInvariantKey() {
        return "revenue.schedule_total_equals_invoice_amount";
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
        int checkedWithSchedules = 0;

        for (var invoice : invoices) {
            if (invoice.getGrandTotal() == null) continue;
            var schedules = scheduleRepository.findByInvoiceId(invoice.getId());
            if (schedules.isEmpty()) continue;
            checkedWithSchedules++;

            BigDecimal scheduleTotal = schedules.stream()
                    .map(s -> s.getAmount() != null ? s.getAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (scheduleTotal.compareTo(invoice.getGrandTotal()) != 0) {
                violations.add(IntegrityViolation.builder()
                        .entityType("INVOICE")
                        .entityId(invoice.getId())
                        .details("scheduleTotal=" + scheduleTotal
                                + " ≠ invoiceGrandTotal=" + invoice.getGrandTotal()
                                + " (delta=" + scheduleTotal.subtract(invoice.getGrandTotal()) + ")")
                        .preview("invoiceNumber=" + invoice.getInvoiceNumber()
                                + ", scheduleRows=" + schedules.size())
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
                        ? "All " + checkedWithSchedules + " invoices with schedules have matching totals"
                        : violations.size() + " invoices have schedule total ≠ grand total")
                .suggestedRepairKey(passed ? null : "revenue.regenerate_recognition_schedule")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
