package com.firstclub.reporting.ops.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Read-model projection of invoice summary data for dashboards and support.
 * NOT the source of truth.  Asynchronously updated from domain events.
 * Fully rebuildable via {@code ProjectionRebuildService}.
 *
 * <p>Primary key: {@code (merchant_id, invoice_id)}.
 */
@Entity
@Table(
    name = "invoice_summary_projection",
    indexes = {
        @Index(name = "idx_isp_merchant_status", columnList = "merchant_id, status"),
        @Index(name = "idx_isp_customer",        columnList = "merchant_id, customer_id")
    }
)
@IdClass(InvoiceSummaryProjectionId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = {"merchantId", "invoiceId"})
public class InvoiceSummaryProjection {

    @Id
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Id
    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "invoice_number", length = 64)
    private String invoiceNumber;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Builder.Default
    @Column(name = "subtotal", nullable = false, precision = 18, scale = 4)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "tax_total", nullable = false, precision = 18, scale = 4)
    private BigDecimal taxTotal = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "grand_total", nullable = false, precision = 18, scale = 4)
    private BigDecimal grandTotal = BigDecimal.ZERO;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Builder.Default
    @Column(name = "overdue_flag", nullable = false)
    private boolean overdueFlag = false;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
