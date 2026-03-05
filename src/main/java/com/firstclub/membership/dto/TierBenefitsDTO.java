package com.firstclub.membership.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Nested DTO representing a membership tier's benefit package.
 * Embedded inside SubscriptionDTO and MembershipPlanDTO responses
 * instead of polluting the parent DTO with 8 flat benefit fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TierBenefitsDTO {

    private BigDecimal discountPercentage;
    private Boolean freeDelivery;
    private Boolean exclusiveDeals;
    private Boolean earlyAccess;
    private Boolean prioritySupport;
    private Integer maxCouponsPerMonth;
    private Integer deliveryDays;
    private String additionalBenefits;
}
