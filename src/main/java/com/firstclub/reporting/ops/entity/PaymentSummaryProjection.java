package com.firstclub.reporting.ops.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Read-model projection of payment intent summary for ops dashboards.
 * Captures captured/refunded/disputed amounts, attempt count, and last gateway
 * signal without querying transactional tables at read time.
 * NOT the source of truth.  Rebuildable from payment events.
 *
 * <p>Primary key: {@code (merchant_id, payment_intent_id)}.
 */
@Entity
@Table(
    name = "payment_summary_projection",
    indexes = {
        @Index(name = "idx_psp_merchant_status", columnList = "merchant_id, status"),
        @Index(name = "idx_psp_customer",        columnList = "merchant_id, customer_id")
    }
)
@IdClass(PaymentSummaryProjectionId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = {"merchantId", "paymentIntentId"})
public class PaymentSummaryProjection {

    @Id
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Id
    @Column(name = "payment_intent_id", nullable = false)
    private Long paymentIntentId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Builder.Default
    @Column(name = "captured_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal capturedAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "refunded_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "disputed_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal disputedAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_gateway", length = 64)
    private String lastGateway;

    @Column(name = "last_failure_category", length = 64)
    private String lastFailureCategory;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
