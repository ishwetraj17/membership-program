package com.firstclub.membership.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A member's savings, aggregated from the auditable savings ledger. Every figure is a sum over
 * persisted ledger rows, so {@code byBenefitType} (and the category/coupon/fee/intro components)
 * reconcile to {@code lifetimeSavings}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SavingsSummaryDTO {
    private Long userId;
    private BigDecimal lifetimeSavings;
    private BigDecimal monthlySavings;
    /** Savings keyed by benefit type (MEMBERSHIP_DISCOUNT, COUPON, DELIVERY_FEE, …). */
    private Map<String, BigDecimal> byBenefitType;
    /** Category-discount savings keyed by product category. */
    private Map<String, BigDecimal> byCategory;
}
