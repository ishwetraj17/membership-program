package com.firstclub.membership.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for MembershipTier — replaces direct entity exposure in API responses.
 * Shields the API contract from internal entity changes and eliminates the need
 * for @JsonIgnore workarounds on the plans list.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MembershipTierDTO {

    private Long id;
    private String name;
    private String description;
    private Integer level;
    private BigDecimal discountPercentage;
    private Boolean freeDelivery;
    private Boolean exclusiveDeals;
    private Boolean earlyAccess;
    private Boolean prioritySupport;
    private Integer maxCouponsPerMonth;
    private Integer deliveryDays;
    private String additionalBenefits;
}
