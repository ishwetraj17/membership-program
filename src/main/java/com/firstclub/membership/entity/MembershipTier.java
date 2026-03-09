package com.firstclub.membership.entity;

import jakarta.persistence.*;
import lombok.*;
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
@EqualsAndHashCode(of = "id")
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
    @ToString.Exclude
    private List<MembershipPlan> plans;
}