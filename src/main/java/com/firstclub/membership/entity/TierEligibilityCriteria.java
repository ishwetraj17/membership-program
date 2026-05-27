package com.firstclub.membership.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Defines the minimum thresholds a user must meet to qualify for a given tier.
 *
 * One row per tier (GOLD and PLATINUM have explicit criteria; SILVER has none —
 * it is the default fallback tier for all users).
 *
 * In production the order data would come from an Order service; for the demo
 * the TierEvaluationService uses deterministic mock values.
 */
@Entity
@Table(name = "tier_eligibility_criteria")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TierEligibilityCriteria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tier_id", nullable = false)
    private MembershipTier tier;

    /** Minimum number of orders placed within the evaluation window. */
    @Column(nullable = false)
    private Integer minOrders;

    /** Minimum cumulative spend (INR) within the evaluation window. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal minMonthlySpend;

    /**
     * Optional cohort code — non-null means only users explicitly assigned to
     * this cohort are eligible for the tier regardless of order metrics.
     */
    @Column
    private String cohortCode;

    /** Rolling window (days) over which order metrics are evaluated. */
    @Column(nullable = false)
    private Integer evaluationPeriodDays;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
