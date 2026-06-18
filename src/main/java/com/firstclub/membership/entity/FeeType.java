package com.firstclub.membership.entity;

/**
 * The ancillary fees a quick-commerce order can carry, each independently waivable by a
 * membership benefit. Goods value is not a fee — it is discounted, not waived.
 */
public enum FeeType {
    DELIVERY,
    HANDLING,
    SMALL_CART,
    SURGE,
    RAIN
}
