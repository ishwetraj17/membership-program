package com.firstclub.membership.dto;

import com.firstclub.membership.entity.Subscription;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for subscription details with user and plan information.
 *
 * Tier benefit fields are now nested inside {@link TierBenefitsDTO}
 * instead of being polluted at the top level.
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

    // Tier summary (name + level stay flat for easy filtering/sorting)
    private String tier;
    private Integer tierLevel;

    // All tier benefit details are nested — keeps the DTO clean
    private TierBenefitsDTO tierBenefits;

    // Cancellation details (if applicable)
    private LocalDateTime cancelledAt;
    private String cancellationReason;
}
