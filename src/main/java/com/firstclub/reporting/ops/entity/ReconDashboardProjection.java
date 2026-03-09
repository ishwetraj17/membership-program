package com.firstclub.reporting.ops.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Read-model projection of per-date reconciliation dashboard metrics.
 * Tracks open mismatch counts by reconciliation layer and resolved totals.
 *
 * <p>{@code merchantId = null} represents a platform-aggregate (cross-merchant)
 * row. The DB unique index treats {@code NULL} as {@code -1} via
 * {@code COALESCE(merchant_id, -1)}.
 *
 * <p>NOT the source of truth.  Rebuildable from {@code ReconReport} and
 * {@code ReconMismatch} data via {@code ProjectionRebuildService}.
 */
@Entity
@Table(
    name = "recon_dashboard_projection",
    indexes = {
        @Index(name = "idx_rdp_business_date", columnList = "business_date")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ReconDashboardProjection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** NULL = platform-aggregate; non-null = merchant-scoped. */
    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    /** Open mismatches at Layer 2 (payment vs ledger). */
    @Builder.Default
    @Column(name = "layer2_open", nullable = false)
    private int layer2Open = 0;

    /** Open mismatches at Layer 3 (ledger vs settlement batch). */
    @Builder.Default
    @Column(name = "layer3_open", nullable = false)
    private int layer3Open = 0;

    /** Open mismatches at Layer 4 (settlement batch vs external statement). */
    @Builder.Default
    @Column(name = "layer4_open", nullable = false)
    private int layer4Open = 0;

    @Builder.Default
    @Column(name = "resolved_count", nullable = false)
    private int resolvedCount = 0;

    /**
     * Absolute delta between expected and actual totals from the source
     * {@code ReconReport}.  Zero when no report exists for this date.
     */
    @Builder.Default
    @Column(name = "unresolved_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal unresolvedAmount = BigDecimal.ZERO;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
