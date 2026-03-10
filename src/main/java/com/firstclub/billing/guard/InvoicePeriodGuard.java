package com.firstclub.billing.guard;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.membership.exception.MembershipException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Guards against overlapping active invoices for the same subscription/period.
 *
 * <p>Rule: a new invoice for a subscription cannot be created if an OPEN or PAID
 * invoice already covers an overlapping billing period for that subscription.
 * This prevents double-billing caused by retries or race conditions.
 */
@Component
@RequiredArgsConstructor
public class InvoicePeriodGuard {

    private final InvoiceRepository invoiceRepository;

    /**
     * Throws {@code MembershipException(OVERLAPPING_INVOICE_PERIOD)} if any active
     * (OPEN or PAID) invoice for {@code subscriptionId} overlaps with
     * [{@code periodStart}, {@code periodEnd}).
     *
     * @param subscriptionId the subscription being billed
     * @param periodStart    inclusive start of the new billing period
     * @param periodEnd      exclusive end of the new billing period
     * @param excludeInvoiceId optional invoice id to exclude from the check
     *                         (use when rebuilding an existing invoice)
     */
    public void assertNoPeriodOverlap(Long subscriptionId,
                                      LocalDateTime periodStart,
                                      LocalDateTime periodEnd,
                                      Long excludeInvoiceId) {
        if (subscriptionId == null || periodStart == null || periodEnd == null) {
            return; // can't check without period data — silently allow
        }

        List<Invoice> overlapping = invoiceRepository
                .findOverlappingActiveInvoices(subscriptionId, periodStart, periodEnd);

        for (Invoice existing : overlapping) {
            if (excludeInvoiceId != null && existing.getId().equals(excludeInvoiceId)) {
                continue;
            }
            throw new MembershipException(
                    "Overlapping active invoice " + existing.getId()
                            + " already covers period ["
                            + existing.getPeriodStart() + " – " + existing.getPeriodEnd()
                            + "] for subscription " + subscriptionId,
                    "OVERLAPPING_INVOICE_PERIOD",
                    HttpStatus.CONFLICT);
        }
    }

    /** Convenience overload — no invoice to exclude. */
    public void assertNoPeriodOverlap(Long subscriptionId,
                                      LocalDateTime periodStart,
                                      LocalDateTime periodEnd) {
        assertNoPeriodOverlap(subscriptionId, periodStart, periodEnd, null);
    }
}
