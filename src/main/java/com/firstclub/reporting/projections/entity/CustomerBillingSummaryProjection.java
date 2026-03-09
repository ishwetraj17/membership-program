package com.firstclub.reporting.projections.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Read-model projection of per-customer billing state per merchant.
 *
 * <p>This table is NOT the source of truth. It is asynchronously updated from
 * domain events and can be fully rebuilt via {@code ProjectionRebuildService}.
 *
 * <p>Primary key: (merchant_id, customer_id).
 */
@Entity
@Table(
    name = "customer_billing_summary_projection",
    indexes = {
        @Index(name = "idx_cbsp_updated_at", columnList = "updated_at")
    }
)
@IdClass(CustomerBillingProjectionId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = {"merchantId", "customerId"})
public class CustomerBillingSummaryProjection {

    @Id
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Id
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Builder.Default
    @Column(name = "active_subscriptions_count", nullable = false)
    private int activeSubscriptionsCount = 0;

    @Builder.Default
    @Column(name = "unpaid_invoices_count", nullable = false)
    private int unpaidInvoicesCount = 0;

    @Builder.Default
    @Column(name = "total_paid_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal totalPaidAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "total_refunded_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal totalRefundedAmount = BigDecimal.ZERO;

    @Column(name = "last_payment_at")
    private LocalDateTime lastPaymentAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
