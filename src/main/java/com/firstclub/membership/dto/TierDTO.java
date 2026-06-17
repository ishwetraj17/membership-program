package com.firstclub.membership.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TierDTO {

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
    /** Human-readable benefit summary derived from the tier's feature flags. */
    private List<String> benefits;
    /** Configurable benefit catalog entries attached to this tier (entity-backed). */
    private List<BenefitDTO> configuredBenefits;
}
