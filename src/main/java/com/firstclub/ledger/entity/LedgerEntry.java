package com.firstclub.ledger.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A journal entry — one per business event (payment captured, refund issued, …).
 *
 * <p>Each entry has two or more {@link LedgerLine} legs that must balance:
 * {@code SUM(DEBIT) == SUM(CREDIT)}.
 *
 * <h3>Phase 10 — Immutability guarantee</h3>
 * All {@link Column} annotations carry {@code updatable = false}.  This prevents
 * Hibernate from issuing UPDATE statements even if a loaded instance is dirtied
 * inside a transaction.  A DB-level trigger ({@code trg_ledger_entries_immutable})
 * installed by migration V56 provides a second layer of protection.
 * Corrections must always be made via a REVERSAL entry.
 */
@Entity
@Table(name = "ledger_entries",
        indexes = @Index(name = "idx_ledger_entries_ref", columnList = "reference_type, reference_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 64, updatable = false)
    private LedgerEntryType entryType;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, length = 32, updatable = false)
    private LedgerReferenceType referenceType;

    @Column(name = "reference_id", nullable = false, updatable = false)
    private Long referenceId;

    @Column(nullable = false, length = 10, updatable = false)
    @Builder.Default
    private String currency = "INR";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(columnDefinition = "TEXT", updatable = false)
    private String metadata;

    // ── Phase 10: Immutability & Reversal Fields ──────────────────────────────

    /**
     * Set only on {@link LedgerEntryType#REVERSAL} entries.
     * References the original entry that this reversal corrects.
     */
    @Column(name = "reversal_of_entry_id", updatable = false)
    private Long reversalOfEntryId;

    /**
     * Optional audit field — records the operator (admin user id) who
     * triggered the posting.  Null for system-generated entries.
     */
    @Column(name = "posted_by_user_id", updatable = false)
    private Long postedByUserId;

    /**
     * Mandatory for {@link LedgerEntryType#REVERSAL}; null for all other types.
     * Explains why the original entry is being corrected.
     */
    @Column(name = "reversal_reason", columnDefinition = "TEXT", updatable = false)
    private String reversalReason;
}
