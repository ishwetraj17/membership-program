package com.firstclub.membership.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Membership plan entity representing subscription options
 * 
 * Each tier (Silver/Gold/Platinum) has 3 plan types:
 * - Monthly (no discount)
 * - Quarterly (5% discount)
 * - Yearly (15% discount)
 * 
 * Implemented by Shwet Raj
 */
@Entity
@Table(name = "membership_plans")
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MembershipPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanType type;

    // Price in INR
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer durationInMonths;

    @Column(nullable = false)
    private Boolean isActive;

    // Each plan belongs to a tier
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tier_id", nullable = false)
    @ToString.Exclude
    private MembershipTier tier;

    // Plans can have multiple subscriptions
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude
    private List<Subscription> subscriptions;

    /**
     * Plan duration types
     */
    public enum PlanType {
        MONTHLY,
        QUARTERLY,
        YEARLY
    }

    /**
     * Enforce data invariants before any INSERT or UPDATE.
     * Catches bad data at persistence time regardless of where the object was built.
     */
    @PrePersist
    @PreUpdate
    private void validateBeforePersist() {
        if (durationInMonths == null || durationInMonths <= 0) {
            throw new IllegalArgumentException(
                "MembershipPlan.durationInMonths must be a positive integer (got: " + durationInMonths + ")");
        }
        if (price == null || price.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                "MembershipPlan.price must be positive (got: " + price + ")");
        }
    }

    /**
     * Calculate effective monthly price
     *
     * For quarterly/yearly plans, this shows the per-month cost
     * which is useful for comparison.
     */
    public BigDecimal getMonthlyPrice() {
        if (durationInMonths == null || durationInMonths == 0) {
            throw new IllegalStateException("durationInMonths must be set before calling getMonthlyPrice()");
        }
        return price.divide(new BigDecimal(durationInMonths), 2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate savings compared to monthly pricing
     * 
     * @param monthlyPrice the base monthly price for comparison
     * @return amount saved by choosing this plan over monthly
     */
    public BigDecimal calculateSavings(BigDecimal monthlyPrice) {
        BigDecimal totalMonthlyPrice = monthlyPrice.multiply(new BigDecimal(durationInMonths));
        return totalMonthlyPrice.subtract(price);
    }
}