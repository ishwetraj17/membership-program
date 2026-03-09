package com.firstclub.integrity.checkers;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.integrity.InvariantChecker;
import com.firstclub.integrity.InvariantResult;
import com.firstclub.integrity.InvariantSeverity;
import com.firstclub.integrity.InvariantViolation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies that every invoice's {@code grandTotal} matches the expected formula:
 * <pre>grandTotal = max(subtotal − discountTotal − creditTotal + taxTotal, 0)</pre>
 *
 * <p>A mismatch indicates that {@link com.firstclub.billing.service.InvoiceTotalService}
 * computed the totals incorrectly, or that one of the component fields was mutated
 * without recalculating the grand total.  PAID invoices with wrong amounts may have
 * caused over- or under-charging.
 */
@Component
@RequiredArgsConstructor
public class InvoicePaymentAmountConsistencyChecker implements InvariantChecker {

    public static final String NAME = "INVOICE_PAYMENT_AMOUNT_CONSISTENCY";
    private static final BigDecimal TOLERANCE = new BigDecimal("0.0100");
    private static final String REPAIR =
            "Re-compute the invoice totals via InvoiceTotalService.recalculate(invoiceId) and compare "
            + "with the stored grandTotal. If the recalculated value differs, update the invoice and "
            + "issue a corrected PDF / credit note as required by your jurisdiction.";

    private final InvoiceRepository invoiceRepository;

    @Override public String getName()               { return NAME; }
    @Override public InvariantSeverity getSeverity() { return InvariantSeverity.HIGH; }

    @Override
    public InvariantResult check() {
        List<Invoice> invoices = invoiceRepository.findByStatus(InvoiceStatus.PAID);

        List<InvariantViolation> violations = new ArrayList<>();
        for (Invoice invoice : invoices) {
            BigDecimal expected = invoice.getSubtotal()
                    .subtract(invoice.getDiscountTotal())
                    .subtract(invoice.getCreditTotal())
                    .add(invoice.getTaxTotal())
                    .max(BigDecimal.ZERO)
                    .setScale(4, RoundingMode.HALF_UP);

            BigDecimal actual = invoice.getGrandTotal().setScale(4, RoundingMode.HALF_UP);

            if (expected.subtract(actual).abs().compareTo(TOLERANCE) > 0) {
                violations.add(InvariantViolation.builder()
                        .entityType("Invoice")
                        .entityId(String.valueOf(invoice.getId()))
                        .description(String.format(
                                "Invoice %d grandTotal=%s but expected=%s "
                                + "(subtotal=%s discountTotal=%s creditTotal=%s taxTotal=%s)",
                                invoice.getId(), actual, expected,
                                invoice.getSubtotal(), invoice.getDiscountTotal(),
                                invoice.getCreditTotal(), invoice.getTaxTotal()))
                        .suggestedRepairAction(REPAIR)
                        .build());
            }
        }

        return violations.isEmpty()
                ? InvariantResult.pass(NAME, getSeverity())
                : InvariantResult.fail(NAME, getSeverity(), violations);
    }
}
