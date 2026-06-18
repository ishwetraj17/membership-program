package com.firstclub.membership.entity;

import java.util.Optional;

/**
 * The kinds of commerce benefit a {@link BenefitRule} can grant.
 *
 * Two families:
 * <ul>
 *   <li>{@code PERCENTAGE_DISCOUNT} — reduces goods value (optionally scoped to a product category).</li>
 *   <li>The {@code *_FEE_WAIVER} types — waive a specific {@link FeeType}.</li>
 * </ul>
 * Adding a benefit kind here is the only code change needed; thresholds, categories and amounts
 * are all data on the rule.
 */
public enum BenefitType {

    PERCENTAGE_DISCOUNT(null),
    DELIVERY_FEE_WAIVER(FeeType.DELIVERY),
    HANDLING_FEE_WAIVER(FeeType.HANDLING),
    SMALL_CART_FEE_WAIVER(FeeType.SMALL_CART),
    SURGE_FEE_WAIVER(FeeType.SURGE),
    RAIN_FEE_WAIVER(FeeType.RAIN);

    private final FeeType feeType;

    BenefitType(FeeType feeType) {
        this.feeType = feeType;
    }

    /** The fee this benefit waives, or empty for non-waiver benefits (e.g. discounts). */
    public Optional<FeeType> waivedFee() {
        return Optional.ofNullable(feeType);
    }

    public boolean isFeeWaiver() {
        return feeType != null;
    }

    public boolean isDiscount() {
        return this == PERCENTAGE_DISCOUNT;
    }
}
