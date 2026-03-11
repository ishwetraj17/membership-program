package com.firstclub.reporting.projections.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Read-model projection of per-customer payment outcomes per merchant.
 *
 * <p>Tracks raw payment success/failure counts and minor-unit (e.g. pence)
 * charged/refunded totals. NOT the source of truth. Asynchronously updated
 * from domain events; fully rebuildable via {@code ProjectionRebuildService}.
 *
 * <p>Primary key: {@code (merchant_id, customer_id)}.
 */
@Entity
@Table(
    name = "customer_payment_summary_projection",
    indexes = {
        @Index(name = "idx_cpsp_updated_at", columnList = "updated_at")
    }
)
@IdClass(CustomerPaymentSummaryProjectionId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = {"merchantId", "customerId"})
public class CustomerPaymentSummaryProjection {

    @Id
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Id
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Builder.Default
    @Column(name = "total_charged_minor", nullable = false)
    private long totalChargedMinor = 0L;

    @Builder.Default
    @Column(name = "total_refunded_minor", nullable = false)
    private long totalRefundedMinor = 0L;

    @Builder.Default
    @Column(name = "successful_payments", nullable = false)
    private int successfulPayments = 0;

    @Builder.Default
    @Column(name = "failed_payments", nullable = false)
    private int failedPayments = 0;

    @Column(name = "last_payment_at")
    private LocalDateTime lastPaymentAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
