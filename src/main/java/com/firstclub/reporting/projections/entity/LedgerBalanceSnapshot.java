package com.firstclub.reporting.projections.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Point-in-time ledger account balance snapshot.
 *
 * <p>Snapshots are generated daily (or on-demand) by aggregating the ledger
 * journal up to the given {@code snapshotDate}. They provide fast balance reads
 * without scanning the full {@code ledger_lines} table at query time.
 *
 * <p>{@code merchantId} is {@code null} for platform-wide (cross-merchant)
 * snapshots. Idempotency is enforced at the service layer (the unique
 * partial indexes in the production migration also enforce it at the DB layer).
 */
@Entity
@Table(
    name = "ledger_balance_snapshots",
    indexes = {
        @Index(name = "idx_lb_snapshots_date", columnList = "snapshot_date")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class LedgerBalanceSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null means this is a platform-wide (cross-merchant) snapshot. */
    @Column(name = "merchant_id")
    private Long merchantId;

    /** References ledger_accounts.id. */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    /** Net balance at snapshot time (sign follows normal-balance convention). */
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal balance;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
