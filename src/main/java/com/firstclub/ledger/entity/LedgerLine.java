package com.firstclub.ledger.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * A single debit or credit leg belonging to a {@link LedgerEntry}.
 *
 * <p>Amounts are always positive; the {@link LineDirection} indicates the sign.
 *
 * <h3>Phase 10 — Immutability guarantee</h3>
 * All {@link Column} annotations carry {@code updatable = false}.
 * A DB-level trigger ({@code trg_ledger_lines_immutable}) installed by migration
 * V56 provides a second layer of protection.
 */
@Entity
@Table(name = "ledger_lines",
        indexes = {
            @Index(name = "idx_ledger_lines_entry_id",   columnList = "entry_id"),
            @Index(name = "idx_ledger_lines_account_id", columnList = "account_id")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class LedgerLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entry_id", nullable = false, updatable = false)
    private Long entryId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6, updatable = false)
    private LineDirection direction;

    @Column(nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal amount;
}
