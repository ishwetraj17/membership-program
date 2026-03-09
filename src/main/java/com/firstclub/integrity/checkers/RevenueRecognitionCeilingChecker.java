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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies that the total amount scheduled for recognition against any PAID
 * invoice never exceeds the invoice's {@code grandTotal}.
 *
 * <p>A recognition-schedule total that exceeds the invoice amount indicates a
 * generation bug where more slices were emitted than the invoice covers.
 */
@Component
@RequiredArgsConstructor
public class RevenueRecognitionCeilingChecker implements InvariantChecker {

    public static final String NAME = "REVENUE_RECOGNITION_CEILING";
    private static final String REPAIR =
            "Delete excess recognition schedule rows for the affected invoice and regenerate via "
            + "RevenueRecognitionScheduleService.regenerateForInvoice(invoiceId).";

    private final InvoiceRepository                    invoiceRepository;
    private final RevenueRecognitionScheduleRepository scheduleRepository;

    @Override public String getName()            { return NAME; }
    @Override public InvariantSeverity getSeverity() { return InvariantSeverity.HIGH; }

    @Override
    public InvariantResult check() {
        List<Invoice> paidInvoices = invoiceRepository.findByStatus(InvoiceStatus.PAID);

        List<InvariantViolation> violations = new ArrayList<>();
        for (Invoice invoice : paidInvoices) {
            BigDecimal scheduled = scheduleRepository.sumTotalAmountByInvoiceId(invoice.getId());
            if (scheduled == null) continue;

            BigDecimal ceiling = invoice.getGrandTotal().compareTo(BigDecimal.ZERO) > 0
                    ? invoice.getGrandTotal()
                    : invoice.getTotalAmount();

            if (scheduled.compareTo(ceiling) > 0) {
                violations.add(InvariantViolation.builder()
                        .entityType("Invoice")
                        .entityId(String.valueOf(invoice.getId()))
                        .description(String.format(
                                "Recognition schedule total %s > invoice grandTotal %s for invoice %d",
                                scheduled, ceiling, invoice.getId()))
                        .suggestedRepairAction(REPAIR)
                        .build());
            }
        }

        return violations.isEmpty()
                ? InvariantResult.pass(NAME, getSeverity())
                : InvariantResult.fail(NAME, getSeverity(), violations);
    }
}
