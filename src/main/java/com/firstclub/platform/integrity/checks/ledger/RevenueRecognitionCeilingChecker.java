package com.firstclub.platform.integrity.checks.ledger;

import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Verifies that the total amount posted from revenue recognition schedules
 * for an invoice does not exceed the invoice's grand total.
 *
 * <p>Over-recognition indicates that deferred revenue was released beyond
 * the total amount billed — a revenue account integrity violation.
 */
@Component
@RequiredArgsConstructor
public class RevenueRecognitionCeilingChecker implements IntegrityChecker {

    private static final int LOOK_BACK_DAYS = 180;
    private static final int PREVIEW_CAP = 50;

    private final RevenueRecognitionScheduleRepository scheduleRepository;
    private final InvoiceRepository invoiceRepository;

    @Override
    public String getInvariantKey() {
        return "ledger.revenue_recognition_within_ceiling";
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

            var schedules = scheduleRepository.findByInvoiceId(invoice.getId());
            if (schedules.isEmpty()) continue;

            // Sum all schedule amounts (PENDING + POSTED + FAILED)
            BigDecimal totalScheduled = schedules.stream()
                    .map(s -> s.getAmount() != null ? s.getAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (totalScheduled.compareTo(invoice.getGrandTotal()) > 0) {
                violations.add(IntegrityViolation.builder()
                        .entityType("INVOICE")
                        .entityId(invoice.getId())
                        .details("totalScheduledRevenue=" + totalScheduled
                                + " exceeds invoiceGrandTotal=" + invoice.getGrandTotal()
                                + " (over-recognition=" + totalScheduled.subtract(invoice.getGrandTotal()) + ")")
                        .preview("invoiceNumber=" + invoice.getInvoiceNumber()
                                + ", scheduleCount=" + schedules.size())
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
                        ? "All revenue recognition schedules are within invoice totals"
                        : violations.size() + " invoices have over-scheduled revenue recognition")
                .suggestedRepairKey(passed ? null : "ledger.void_excess_revenue_schedule")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
