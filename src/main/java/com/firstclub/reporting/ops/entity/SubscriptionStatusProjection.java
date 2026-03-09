package com.firstclub.reporting.ops.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Read-model projection of per-subscription operational status.
 * NOT the source of truth.  Asynchronously updated from domain events.
 * Fully rebuildable via {@code ProjectionRebuildService}.
 *
 * <p>Primary key: {@code (merchant_id, subscription_id)}.
 */
@Entity
@Table(
    name = "subscription_status_projection",
    indexes = {
        @Index(name = "idx_ssp_merchant_status", columnList = "merchant_id, status"),
        @Index(name = "idx_ssp_customer",        columnList = "merchant_id, customer_id")
    }
)
@IdClass(SubscriptionStatusProjectionId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = {"merchantId", "subscriptionId"})
public class SubscriptionStatusProjection {

    @Id
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Id
    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "next_billing_at")
    private LocalDateTime nextBillingAt;

    /** Latest dunning attempt status for this subscription; null if no dunning. */
    @Column(name = "dunning_state", length = 32)
    private String dunningState;

    @Builder.Default
    @Column(name = "unpaid_invoice_count", nullable = false)
    private int unpaidInvoiceCount = 0;

    /** Status name of the most recent payment intent for this subscription. */
    @Column(name = "last_payment_status", length = 64)
    private String lastPaymentStatus;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
