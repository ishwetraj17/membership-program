package com.firstclub.billing.service;

import com.firstclub.billing.entity.Invoice;

/**
 * Recomputes the invoice total-breakdown columns from the current set of
 * {@code InvoiceLine} rows attached to the invoice.
 *
 * <p>Breakdown semantics:
 * <ul>
 *   <li>{@code subtotal}      — sum of PLAN_CHARGE + PRORATION lines (positive)</li>
 *   <li>{@code taxTotal}      — sum of TAX lines (positive)</li>
 *   <li>{@code discountTotal} — absolute value of DISCOUNT lines (stored negative)</li>
 *   <li>{@code creditTotal}   — absolute value of CREDIT_APPLIED lines (stored negative)</li>
 *   <li>{@code grandTotal}    — {@code max(subtotal − discountTotal − creditTotal + taxTotal, 0)}</li>
 * </ul>
 * {@code invoice.totalAmount} is also updated to {@code grandTotal} for
 * backward compatibility with legacy code.
 */
public interface InvoiceTotalService {

    /**
     * Recompute and persist all breakdown fields on {@code invoice}.
     *
     * @param invoice the invoice to update (must already be managed / persisted)
     * @return the updated invoice
     */
    Invoice recomputeTotals(Invoice invoice);
}
