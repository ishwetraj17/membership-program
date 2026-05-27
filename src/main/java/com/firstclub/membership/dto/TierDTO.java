package com.firstclub.membership.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Public view of a membership tier — decouples the API contract from the
 * persistence model so the controller layer never depends on JPA entities.
 */
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
}
