package com.firstclub.membership.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.util.List;

/**
 * Membership tier entity - Silver, Gold, Platinum
 * 
 * Each tier has different benefits and discount percentages.
 * Silver = 5%, Gold = 10%, Platinum = 15% discounts
 * 
 * Implemented by Shwet Raj
 */
@Entity
@Table(name = "membership_tiers")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MembershipTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name; // SILVER, GOLD, PLATINUM

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private Integer level; // 1=Silver, 2=Gold, 3=Platinum

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercentage;

    @Column(nullable = false)
    private Boolean freeDelivery;

    @Column(nullable = false)
    private Boolean exclusiveDeals;

    @Column(nullable = false)
    private Boolean earlyAccess;

    @Column(nullable = false)
    private Boolean prioritySupport;

    @Column(nullable = false)
    private Integer maxCouponsPerMonth;

    // Delivery time in days
    @Column(nullable = false)
    private Integer deliveryDays;

    @Column(columnDefinition = "TEXT")
    private String additionalBenefits;

    // Each tier can have multiple plans (monthly, quarterly, yearly)
    @OneToMany(mappedBy = "tier", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore  // Prevents Jackson serialization issues in REST endpoints
    private List<MembershipPlan> plans;

    /**
     * Creates default membership tiers for the system
     * 
     * TODO: Maybe move this to a separate service class later
     */
    public static MembershipTier[] getDefaultTiers() {
        return new MembershipTier[] {
            // Silver tier - basic benefits
            MembershipTier.builder()
                .name("SILVER")
                .description("Essential benefits for new members")
                .level(1)
                .discountPercentage(new BigDecimal("5.00"))
                .freeDelivery(false)
                .exclusiveDeals(false)
                .earlyAccess(false)
                .prioritySupport(false)
                .maxCouponsPerMonth(2)
                .deliveryDays(5)
                .additionalBenefits("Basic member perks and content access")
                .build(),
            
            // Gold tier - premium benefits
            MembershipTier.builder()
                .name("GOLD")
                .description("Premium benefits with free delivery")
                .level(2)
                .discountPercentage(new BigDecimal("10.00"))
                .freeDelivery(true)
                .exclusiveDeals(true)
                .earlyAccess(true)
                .prioritySupport(false)
                .maxCouponsPerMonth(5)
                .deliveryDays(3)
                .additionalBenefits("Free delivery, exclusive deals, early sale access")
                .build(),
            
            // Platinum tier - ultimate benefits
            MembershipTier.builder()
                .name("PLATINUM")
                .description("Ultimate tier with all premium features")
                .level(3)
                .discountPercentage(new BigDecimal("15.00"))
                .freeDelivery(true)
                .exclusiveDeals(true)
                .earlyAccess(true)
                .prioritySupport(true)
                .maxCouponsPerMonth(10)
                .deliveryDays(1)
                .additionalBenefits("All benefits plus priority support and same-day delivery")
                .build()
        };
    }
}