package com.firstclub.membership.entity;

/**
 * The kinds of realised member savings tracked in the savings ledger. Fee-waiver types map 1:1 to
 * {@link FeeType}; discounts split into whole-cart membership vs category-targeted; plus coupon and
 * introductory-offer savings.
 */
public enum SavingsType {
    MEMBERSHIP_DISCOUNT,
    CATEGORY_DISCOUNT,
    COUPON,
    DELIVERY_FEE,
    HANDLING_FEE,
    SMALL_CART_FEE,
    SURGE_FEE,
    RAIN_FEE,
    INTRO_OFFER;

    /** The savings type recorded when a given fee is waived. */
    public static SavingsType forFee(FeeType fee) {
        return switch (fee) {
            case DELIVERY -> DELIVERY_FEE;
            case HANDLING -> HANDLING_FEE;
            case SMALL_CART -> SMALL_CART_FEE;
            case SURGE -> SURGE_FEE;
            case RAIN -> RAIN_FEE;
        };
    }
}
