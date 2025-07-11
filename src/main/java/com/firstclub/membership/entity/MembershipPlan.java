package com.firstclub.membership.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

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
    private MembershipTier tier;

    // Plans can have multiple subscriptions
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
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
     * Calculate effective monthly price
     * 
     * For quarterly/yearly plans, this shows the per-month cost
     * which is useful for comparison.
     */
    public BigDecimal getMonthlyPrice() {
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