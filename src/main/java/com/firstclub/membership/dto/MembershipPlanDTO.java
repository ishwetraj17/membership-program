package com.firstclub.membership.dto;

import com.firstclub.membership.entity.MembershipPlan;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for membership plan information with benefits
 * 
 * Combines plan details with tier benefits for easy API consumption.
 * Shows pricing in INR and savings calculations.
 * 
 * Implemented by Shwet Raj
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MembershipPlanDTO {

    private Long id;
    private String name;
    private String description;
    private MembershipPlan.PlanType type;
    
    // Pricing information in INR
    private BigDecimal price;
    private Integer durationInMonths;
    private BigDecimal monthlyPrice;
    private BigDecimal savings; // compared to monthly pricing
    
    // Tier information
    private String tier;
    private Integer tierLevel;
    
    // Benefits from tier
    private BigDecimal discountPercentage;
    private Boolean freeDelivery;
    private Boolean exclusiveDeals;
    private Boolean earlyAccess;
    private Boolean prioritySupport;
    private Integer maxCouponsPerMonth;
    private Integer deliveryDays;
    private String additionalBenefits;
    
    private Boolean isActive;
}