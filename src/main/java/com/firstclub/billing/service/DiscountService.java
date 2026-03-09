package com.firstclub.billing.service;

import com.firstclub.billing.dto.ApplyDiscountRequestDTO;
import com.firstclub.billing.dto.DiscountCreateRequestDTO;
import com.firstclub.billing.dto.DiscountResponseDTO;
import com.firstclub.billing.dto.InvoiceSummaryDTO;

import java.util.List;

public interface DiscountService {

    /** Create a new discount for a merchant. */
    DiscountResponseDTO createDiscount(Long merchantId, DiscountCreateRequestDTO request);

    /** List all discounts for a merchant (newest first). */
    List<DiscountResponseDTO> listDiscounts(Long merchantId);

    /** Fetch a single discount by ID, scoped to the merchant. */
    DiscountResponseDTO getDiscount(Long merchantId, Long discountId);

    /**
     * Apply a discount code to an open invoice.
     *
     * <p>Validation:
     * <ul>
     *   <li>Discount must be ACTIVE and within its validity window.</li>
     *   <li>Invoice must be in OPEN state.</li>
     *   <li>maxRedemptions and perCustomerLimit are enforced when set.</li>
     *   <li>A discount can only be applied once per invoice.</li>
     * </ul>
     */
    InvoiceSummaryDTO applyDiscountToInvoice(Long merchantId, Long invoiceId, ApplyDiscountRequestDTO request);
}
