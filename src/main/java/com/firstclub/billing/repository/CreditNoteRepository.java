package com.firstclub.billing.repository;

import com.firstclub.billing.entity.CreditNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CreditNoteRepository extends JpaRepository<CreditNote, Long> {

    List<CreditNote> findByUserId(Long userId);

    /**
     * Returns credit notes for a user that still have an available balance
     * (amount > used_amount), ordered oldest-first (FIFO application).
     */
    @Query("""
            SELECT c FROM CreditNote c
            WHERE c.userId = :userId
              AND c.usedAmount < c.amount
            ORDER BY c.createdAt ASC
            """)
    List<CreditNote> findAvailableByUserId(@Param("userId") Long userId);

    // ── Phase 17: customer-scoped credit query ──────────────────────────────────────
    /**
     * Returns ALL credit notes (including exhausted) for a customer,
     * newest-first, for the customer credits API.
     */
    @Query("""
            SELECT c FROM CreditNote c
            WHERE c.userId = :userId
            ORDER BY c.createdAt DESC
            """)
    List<CreditNote> findAllByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);
}
