package com.firstclub.catalog.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * An immutable pricing snapshot for a {@link Price} that is valid during a
 * specific time window.
 *
 * <p>Price versions model the commercial truth at billing time:
 * <ul>
 *   <li>When a version is created the previous open-ended version (where
 *       {@code effective_to} is null) is closed by setting its
 *       {@code effective_to = new_version.effective_from}.</li>
 *   <li>Future-dated versions are allowed — they become active when
 *       {@code effective_from} arrives.</li>
 *   <li>Existing subscriptions that were locked to an earlier version are
 *       not retroactively affected unless {@code grandfather_existing_subscriptions}
 *       is {@code false} (i.e. they get the new price on their next renewal).</li>
 * </ul>
 *
 * <p>There is intentionally no {@code updated_at} column — versions are
 * immutable once created.
 */
@Entity
@Table(
    name = "price_versions",
    indexes = {
        @Index(name = "idx_price_versions_price_effective",
               columnList = "price_id, effective_from DESC")
    }
)
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The price this version belongs to. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "price_id", nullable = false)
    @ToString.Exclude
    private Price price;

    /** Start of the window during which this version is authoritative. */
    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    /**
     * End of the window (exclusive).  {@code null} means "currently open-ended /
     * in effect until the next version supersedes this one."
     */
    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    /** The charged amount during this window. */
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    /** ISO 4217 currency code for this window. */
    @Column(nullable = false, length = 10)
    private String currency;

    /**
     * When {@code true}, existing subscriptions that are already billing at the
     * previous version's rate will be shielded — they continue at the old rate
     * until they churn.  When {@code false}, all active subscriptions switch to
     * the new rate at their next renewal.
     */
    @Column(name = "grandfather_existing_subscriptions", nullable = false)
    @Builder.Default
    private boolean grandfatherExistingSubscriptions = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
