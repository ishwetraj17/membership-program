package com.firstclub.membership.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Priced cart with the user's membership benefits applied — the membership program's
 * integration point with the shopping/checkout journey.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutQuoteResponse {

    private Long userId;
    /** Active membership tier driving the benefits, or null if the user has no active membership. */
    private String membershipTier;

    private BigDecimal subtotal;
    private BigDecimal discountPercentage;
    private BigDecimal discountAmount;

    private BigDecimal deliveryFee;
    private boolean deliveryWaived;

    // Quick-commerce ancillary fees as charged (net of any waiver). Zero when not supplied.
    private BigDecimal handlingFee;
    private BigDecimal smallCartFee;
    private BigDecimal surgeFee;
    private BigDecimal rainFee;
    /** Sum of all fees actually charged (delivery + handling + small-cart + surge + rain). */
    private BigDecimal totalFees;
    /** Names of fees waived by membership benefits, e.g. ["DELIVERY", "HANDLING"]. */
    private List<String> waivedFees;

    private String couponCode;
    private BigDecimal couponDiscount;

    private BigDecimal total;

    private List<String> appliedBenefits;
    private Integer couponsAvailable;
}
