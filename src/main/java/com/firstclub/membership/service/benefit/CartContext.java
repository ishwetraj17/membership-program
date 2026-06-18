package com.firstclub.membership.service.benefit;

import com.firstclub.membership.entity.FeeType;
import com.firstclub.membership.entity.ProductCategory;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The cart as the benefit engine needs to see it: the goods subtotal, the subtotal broken down by
 * recognised product category (for category-targeted discounts), and the ancillary fees in play
 * (for fee waivers). Goods that don't map to a known category are still in {@code subtotal} but not
 * in {@code categorySubtotals}.
 */
@Getter
@Builder
public class CartContext {

    private final BigDecimal subtotal;
    private final Map<ProductCategory, BigDecimal> categorySubtotals;
    private final Map<FeeType, BigDecimal> fees;
}
