package com.firstclub.reporting.projections.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Read-model projection of a user's running ledger balance per merchant.
 *
 * <p>Derived from domain events. NOT the source of truth. Distinct from
 * {@code LedgerBalanceSnapshot} (point-in-time daily snapshot); this record
 * is continuously updated on every ledger-related event.
 *
 * <p>Primary key: {@code (merchant_id, user_id)}.
 */
@Entity
@Table(
    name = "ledger_balance_projection",
    indexes = {
        @Index(name = "idx_lbp_updated_at", columnList = "updated_at")
    }
)
@IdClass(LedgerBalanceProjectionId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = {"merchantId", "userId"})
public class LedgerBalanceProjection {

    @Id
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Builder.Default
    @Column(name = "total_credits_minor", nullable = false)
    private long totalCreditsMinor = 0L;

    @Builder.Default
    @Column(name = "total_debits_minor", nullable = false)
    private long totalDebitsMinor = 0L;

    @Builder.Default
    @Column(name = "net_balance_minor", nullable = false)
    private long netBalanceMinor = 0L;

    @Builder.Default
    @Column(name = "entry_count", nullable = false)
    private int entryCount = 0;

    @Column(name = "last_entry_at")
    private LocalDateTime lastEntryAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
