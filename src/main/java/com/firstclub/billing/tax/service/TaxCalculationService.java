package com.firstclub.billing.tax.service;

import com.firstclub.billing.dto.InvoiceSummaryDTO;
import com.firstclub.billing.tax.dto.InvoiceTaxBreakdownDTO;

public interface TaxCalculationService {

    /**
     * Computes the GST breakdown for a given invoice without persisting any lines.
     * Returns the full breakdown including CGST/SGST or IGST line details.
     */
    InvoiceTaxBreakdownDTO getTaxBreakdown(Long merchantId, Long invoiceId, Long customerId);

    /**
     * Deletes any existing CGST/SGST/IGST lines on the invoice, recomputes GST
     * based on current subtotal and discount lines, saves the new tax lines, and
     * returns the recalculated invoice summary.
     */
    InvoiceSummaryDTO recalculateTax(Long merchantId, Long invoiceId, Long customerId);
}
