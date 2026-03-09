package com.firstclub.reporting.projections.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Read-model projection of daily operational KPIs per merchant.
 *
 * <p>This table is NOT the source of truth. It is asynchronously updated from
 * domain events and can be fully rebuilt via {@code ProjectionRebuildService}.
 *
 * <p>Primary key: (merchant_id, business_date).
 */
@Entity
@Table(
    name = "merchant_daily_kpis_projection",
    indexes = {
        @Index(name = "idx_mdkp_business_date", columnList = "business_date")
    }
)
@IdClass(MerchantKpiProjectionId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = {"merchantId", "businessDate"})
public class MerchantDailyKpiProjection {

    @Id
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Id
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Builder.Default
    @Column(name = "invoices_created", nullable = false)
    private int invoicesCreated = 0;

    @Builder.Default
    @Column(name = "invoices_paid", nullable = false)
    private int invoicesPaid = 0;

    @Builder.Default
    @Column(name = "payments_captured", nullable = false)
    private int paymentsCaptured = 0;

    @Builder.Default
    @Column(name = "refunds_completed", nullable = false)
    private int refundsCompleted = 0;

    @Builder.Default
    @Column(name = "disputes_opened", nullable = false)
    private int disputesOpened = 0;

    @Builder.Default
    @Column(name = "revenue_recognized", nullable = false, precision = 18, scale = 4)
    private BigDecimal revenueRecognized = BigDecimal.ZERO;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
