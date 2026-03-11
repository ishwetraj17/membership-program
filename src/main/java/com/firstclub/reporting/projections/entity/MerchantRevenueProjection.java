package com.firstclub.reporting.projections.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Read-model projection of all-time revenue and churn counters per merchant.
 *
 * <p>A single row per merchant containing minor-unit (e.g. pence) running
 * totals. NOT the source of truth. Asynchronously updated from domain events.
 * Distinct from {@code MerchantDailyKpiProjection} (date-bucketed counters).
 *
 * <p>Primary key: {@code merchant_id}.
 */
@Entity
@Table(
    name = "merchant_revenue_projection",
    indexes = {
        @Index(name = "idx_mrp_updated_at", columnList = "updated_at")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "merchantId")
public class MerchantRevenueProjection {

    @Id
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Builder.Default
    @Column(name = "total_revenue_minor", nullable = false)
    private long totalRevenueMinor = 0L;

    @Builder.Default
    @Column(name = "total_refunds_minor", nullable = false)
    private long totalRefundsMinor = 0L;

    @Builder.Default
    @Column(name = "net_revenue_minor", nullable = false)
    private long netRevenueMinor = 0L;

    @Builder.Default
    @Column(name = "active_subscriptions", nullable = false)
    private int activeSubscriptions = 0;

    @Builder.Default
    @Column(name = "churned_subscriptions", nullable = false)
    private int churnedSubscriptions = 0;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
