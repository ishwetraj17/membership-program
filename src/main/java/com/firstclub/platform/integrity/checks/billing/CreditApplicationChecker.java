package com.firstclub.platform.integrity.checks.billing;

import com.firstclub.billing.entity.CreditNote;
import com.firstclub.billing.entity.InvoiceLine;
import com.firstclub.billing.entity.InvoiceLineType;
import com.firstclub.billing.repository.CreditNoteRepository;
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
 * Verifies that the total credit applied across all invoices for a user does
 * not exceed the total available credit note balance for that user.
 *
 * <p>Formula: {@code SUM(CREDIT_APPLIED lines) <= SUM(creditNote.amount)} per user.
 */
@Component
@RequiredArgsConstructor
public class CreditApplicationChecker implements IntegrityChecker {

    private static final int LOOK_BACK_DAYS = 90;
    private static final int PREVIEW_CAP = 50;

    private final InvoiceRepository    invoiceRepository;
    private final InvoiceLineRepository invoiceLineRepository;
    private final CreditNoteRepository  creditNoteRepository;

    @Override
    public String getInvariantKey() {
        return "billing.credit_not_over_applied";
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

        // Group CREDIT_APPLIED amounts by userId
        java.util.Map<Long, BigDecimal> appliedByUser = new java.util.HashMap<>();
        for (var invoice : invoices) {
            if (invoice.getUserId() == null) continue;
            List<InvoiceLine> creditLines = invoiceLineRepository
                    .findByInvoiceId(invoice.getId())
                    .stream()
                    .filter(l -> InvoiceLineType.CREDIT_APPLIED.equals(l.getLineType()))
                    .collect(Collectors.toList());

            BigDecimal applied = creditLines.stream()
                    .map(InvoiceLine::getAmount)
                    .map(BigDecimal::abs)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            appliedByUser.merge(invoice.getUserId(), applied, BigDecimal::add);
        }

        List<IntegrityViolation> violations = new ArrayList<>();

        for (var entry : appliedByUser.entrySet()) {
            Long userId = entry.getKey();
            BigDecimal totalApplied = entry.getValue();

            List<CreditNote> creditNotes = creditNoteRepository.findByUserId(userId);
            BigDecimal totalAvailable = creditNotes.stream()
                    .map(cn -> cn.getAmount() != null ? cn.getAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (totalApplied.compareTo(totalAvailable) > 0) {
                violations.add(IntegrityViolation.builder()
                        .entityType("USER_CREDIT")
                        .entityId(userId)
                        .details("creditApplied=" + totalApplied
                                + " exceeds totalCreditAvailable=" + totalAvailable
                                + " (over-applied=" + totalApplied.subtract(totalAvailable) + ")")
                        .preview("userId=" + userId)
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
                        ? "No over-applied credit notes detected"
                        : violations.size() + " users have applied more credit than their available balance")
                .suggestedRepairKey(passed ? null : "billing.void_excess_credit_application")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
