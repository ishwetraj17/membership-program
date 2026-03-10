package com.firstclub.billing.guard;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.entity.InvoiceLine;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceLineRepository;
import com.firstclub.membership.exception.MembershipException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Validates billing invariants before mutating operations are allowed.
 *
 * <p>Invariants enforced:
 * <ol>
 *   <li><strong>Total correctness</strong> — invoice {@code grandTotal} must equal the
 *       computed sum-of-lines formula (subtotal − discount − credit + tax).</li>
 *   <li><strong>Terminal-state guard</strong> — PAID invoice cannot be voided without
 *       an explicit refund/reversal flag.</li>
 *   <li><strong>Credit-application cap</strong> — credit being applied must not exceed
 *       the available balance on the credit note.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class InvoiceInvariantService {

    private final InvoiceLineRepository invoiceLineRepository;

    /**
     * Asserts that {@code invoice.grandTotal} matches the sum-of-lines formula.
     * Throws {@code MembershipException(INVOICE_TOTAL_MISMATCH)} if diverged.
     */
    public void assertTotalMatchesLines(Invoice invoice) {
        List<InvoiceLine> lines = invoiceLineRepository.findByInvoiceId(invoice.getId());

        BigDecimal subtotal      = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        BigDecimal creditTotal   = BigDecimal.ZERO;
        BigDecimal taxTotal      = BigDecimal.ZERO;

        for (InvoiceLine line : lines) {
            BigDecimal amount = line.getAmount();
            switch (line.getLineType()) {
                case PLAN_CHARGE, PRORATION    -> subtotal = subtotal.add(amount);
                case TAX, CGST, SGST, IGST     -> taxTotal = taxTotal.add(amount);
                case DISCOUNT                  -> discountTotal = discountTotal.add(amount.abs());
                case CREDIT_APPLIED            -> creditTotal = creditTotal.add(amount.abs());
            }
        }

        BigDecimal expected = subtotal.subtract(discountTotal).subtract(creditTotal)
                .add(taxTotal).max(BigDecimal.ZERO);

        if (expected.compareTo(invoice.getGrandTotal()) != 0) {
            throw new MembershipException(
                    "Invoice " + invoice.getId() + " total mismatch: grandTotal="
                            + invoice.getGrandTotal() + " but sum-of-lines=" + expected,
                    "INVOICE_TOTAL_MISMATCH",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    /**
     * Asserts that a PAID invoice is not being voided without an explicit
     * reversal/refund path authorisation.
     *
     * @param invoice     the invoice to be voided
     * @param hasRefundPath {@code true} when the caller has verified a valid
     *                      refund or manual reversal record exists
     */
    public void assertVoidAllowed(Invoice invoice, boolean hasRefundPath) {
        if (invoice.getStatus() == InvoiceStatus.PAID && !hasRefundPath) {
            throw new MembershipException(
                    "Invoice " + invoice.getId() + " is PAID and cannot be voided without a refund/reversal path",
                    "PAID_INVOICE_VOID_BLOCKED",
                    HttpStatus.CONFLICT);
        }
    }

    /**
     * Asserts that {@code creditToApply} does not exceed {@code availableBalance}.
     */
    public void assertCreditWithinBalance(BigDecimal creditToApply, BigDecimal availableBalance, Long creditNoteId) {
        if (creditToApply.compareTo(availableBalance) > 0) {
            throw new MembershipException(
                    "Credit application of " + creditToApply
                            + " exceeds available balance " + availableBalance
                            + " on credit note " + creditNoteId,
                    "CREDIT_EXCEEDS_BALANCE",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
}
