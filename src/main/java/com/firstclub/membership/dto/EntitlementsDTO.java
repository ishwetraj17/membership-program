package com.firstclub.membership.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Stable, cache-friendly public contract describing what a user is currently entitled to.
 *
 * This is the integration point for the commerce/checkout platform: checkout owns pricing and
 * asks membership "what does this user get?" rather than handing over the cart to be priced.
 *
 * Designed to be safe to cache and serialize (no JPA entities, timestamps as ISO-8601 strings),
 * and to always be answerable — a non-member or a degraded lookup yields a valid "no benefits"
 * response rather than an error, so checkout never blocks on membership.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntitlementsDTO {

    private Long userId;

    /** True when the user has a currently-active membership. */
    private boolean member;

    /** Active subscription status (e.g. ACTIVE), or null for a non-member. */
    private String subscriptionStatus;

    private String plan;
    private String tier;
    private Integer tierLevel;

    /** Extra member discount percentage applied by the commerce pricing engine (0 for non-members). */
    private BigDecimal discountPercentage;

    private boolean freeDelivery;
    private boolean prioritySupport;
    private boolean exclusiveDeals;
    private boolean earlyAccess;

    private Integer maxCouponsPerMonth;
    private Integer deliveryDays;

    /** Machine-readable fee waivers the member is entitled to (e.g. DELIVERY). */
    private List<String> feeWaivers;

    /** Human-readable benefit summary. */
    private List<String> benefits;

    /** Membership expiry as an ISO-8601 timestamp (null for non-members). */
    private String membershipExpiry;
    private long daysRemaining;

    /** Lifetime savings (member + coupon discounts across the user's orders), if available. */
    private BigDecimal totalSavings;

    /** Whether this response is a safe fallback produced because the lookup degraded. */
    private boolean fallback;
}
