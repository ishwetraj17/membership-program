package com.firstclub.membership.dto;

import com.firstclub.membership.entity.Subscription;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for subscription details with user and plan information
 * 
 * Complete subscription view including user details, plan benefits,
 * and current status for API responses.
 * 
 * Implemented by Shwet Raj
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionDTO {

    // Subscription details
    private Long id;
    private Subscription.SubscriptionStatus status;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private LocalDateTime nextBillingDate;
    private BigDecimal paidAmount;
    private Boolean autoRenewal;
    private Long daysRemaining;
    private Boolean isActive;
    
    // User information
    private Long userId;
    private String userName;
    private String userEmail;
    
    // Plan information
    private Long planId;
    private String planName;
    private String planType;
    
    // Tier information and benefits
    private String tier;
    private Integer tierLevel;
    private BigDecimal discountPercentage;
    private Boolean freeDelivery;
    private Boolean exclusiveDeals;
    private Boolean earlyAccess;
    private Boolean prioritySupport;
    private Integer maxCouponsPerMonth;
    private Integer deliveryDays;
    private String additionalBenefits;
    
    // Cancellation details (if applicable)
    private LocalDateTime cancelledAt;
    private String cancellationReason;
}