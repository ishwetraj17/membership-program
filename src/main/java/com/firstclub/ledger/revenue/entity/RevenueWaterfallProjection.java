package com.firstclub.ledger.revenue.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Materialised daily revenue waterfall snapshot for one merchant.
 *
 * <p>One row per {@code (merchant_id, business_date)}.  Updated by
 * {@link com.firstclub.ledger.revenue.service.RevenueWaterfallProjectionService}
 * after each recognition run via an UPSERT (ON CONFLICT DO UPDATE).
 *
 * <p>Waterfall flow for a single day:
 * <pre>
 *   deferred_closing = deferred_opening + billed_amount
 *                    - recognized_amount - refunded_amount - disputed_amount
 * </pre>
 *
 * <p>Columns reserved for future phases (populated as 0 in Phase 14):
 * <ul>
 *   <li>{@code billed_amount} — invoices finalised (PAID) on this date</li>
 *   <li>{@code deferred_opening} / {@code deferred_closing} — running deferred balance</li>
 *   <li>{@code refunded_amount} — refunds applied on this date</li>
 *   <li>{@code disputed_amount} — disputes opened on this date</li>
 * </ul>
 */
@Entity
@Table(name = "revenue_waterfall_projection",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_waterfall_merchant_date",
                columnNames = {"merchant_id", "business_date"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = {"merchantId", "businessDate"})
public class RevenueWaterfallProjection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    /** Revenue billed (invoices PAID) on this date. */
    @Column(name = "billed_amount", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal billedAmount = BigDecimal.ZERO;

    /** Deferred revenue balance at the opening of the day. */
    @Column(name = "deferred_opening", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal deferredOpening = BigDecimal.ZERO;

    /** Deferred revenue balance at the close of the day. */
    @Column(name = "deferred_closing", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal deferredClosing = BigDecimal.ZERO;

    /** Revenue recognised (POSTED) on this date. */
    @Column(name = "recognized_amount", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal recognizedAmount = BigDecimal.ZERO;

    /** Refunds applied on this date (reserved; 0 in Phase 14). */
    @Column(name = "refunded_amount", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    /** Disputes opened on this date (reserved; 0 in Phase 14). */
    @Column(name = "disputed_amount", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal disputedAmount = BigDecimal.ZERO;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
