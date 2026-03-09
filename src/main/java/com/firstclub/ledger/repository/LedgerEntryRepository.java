package com.firstclub.ledger.repository;

import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.entity.LedgerReferenceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByReferenceTypeAndReferenceId(LedgerReferenceType referenceType, Long referenceId);

    /** Phase 10: find the reversal entry for a given original entry, if one exists. */
    Optional<LedgerEntry> findByReversalOfEntryId(Long originalEntryId);

    /** Phase 10: idempotency guard — true if a reversal already exists for the given entry. */
    boolean existsByReversalOfEntryId(Long originalEntryId);

    // ── Phase 11: integrity-check queries ────────────────────────────────────

    /** All entries whose createdAt is in the future — should be empty in a healthy system. */
    List<LedgerEntry> findByCreatedAtAfter(java.time.LocalDateTime timestamp);

    /** All entries of a specific type — used by SettlementLedgerCompletenessChecker. */
    List<LedgerEntry> findByEntryType(com.firstclub.ledger.entity.LedgerEntryType type);
}
