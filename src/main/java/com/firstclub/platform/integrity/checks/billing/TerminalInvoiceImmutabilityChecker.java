package com.firstclub.platform.integrity.checks.billing;

import com.firstclub.billing.entity.InvoiceLine;
import com.firstclub.billing.model.InvoiceStatus;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Verifies that terminal invoices (PAID, VOID, UNCOLLECTIBLE) have
 * internally consistent totals — grand_total matches the sum of lines,
 * ensuring no line was mutated after the invoice was finalised.
 *
 * <p>A VOID invoice should have a grandTotal of zero or match its line sum
 * exactly. Any mismatch indicates post-terminal mutation.
 */
@Component
@RequiredArgsConstructor
public class TerminalInvoiceImmutabilityChecker implements IntegrityChecker {

    private static final Set<InvoiceStatus> TERMINAL_STATUSES =
            Set.of(InvoiceStatus.PAID, InvoiceStatus.VOID, InvoiceStatus.UNCOLLECTIBLE);
    private static final int LOOK_BACK_DAYS = 180;
    private static final int PREVIEW_CAP = 50;

    private final InvoiceRepository    invoiceRepository;
    private final InvoiceLineRepository invoiceLineRepository;

    @Override
    public String getInvariantKey() {
        return "billing.terminal_invoice_immutability";
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
            if (!TERMINAL_STATUSES.contains(invoice.getStatus())) continue;
            if (invoice.getGrandTotal() == null) continue;

            List<InvoiceLine> lines = invoiceLineRepository.findByInvoiceId(invoice.getId());
            BigDecimal lineSum = lines.stream()
                    .map(InvoiceLine::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // For VOID invoices: a zero grand_total with zero lines is valid
            // For all terminal: grand_total must equal line sum
            if (lineSum.compareTo(invoice.getGrandTotal()) != 0) {
                violations.add(IntegrityViolation.builder()
                        .entityType("INVOICE")
                        .entityId(invoice.getId())
                        .details("Terminal invoice with grandTotal=" + invoice.getGrandTotal()
                                + " ≠ lineSum=" + lineSum + " — possible post-terminal mutation")
                        .preview("status=" + invoice.getStatus()
                                + ", invoiceNumber=" + invoice.getInvoiceNumber())
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
                        ? "All terminal invoices are internally consistent"
                        : violations.size() + " terminal invoices show grand_total ≠ line sum (possible mutation)")
                .suggestedRepairKey(passed ? null : "billing.audit_terminal_invoice_mutation")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
