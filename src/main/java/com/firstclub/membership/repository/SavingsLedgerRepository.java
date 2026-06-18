package com.firstclub.membership.repository;

import com.firstclub.membership.entity.SavingsLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface SavingsLedgerRepository extends JpaRepository<SavingsLedgerEntry, Long> {

    @Query("SELECT COALESCE(SUM(s.amount), 0) FROM SavingsLedgerEntry s WHERE s.userId = :userId")
    BigDecimal lifetimeSavings(@Param("userId") Long userId);

    @Query("SELECT COALESCE(SUM(s.amount), 0) FROM SavingsLedgerEntry s " +
           "WHERE s.userId = :userId AND s.occurredAt >= :since")
    BigDecimal savingsSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    @Query("SELECT s.savingsType, COALESCE(SUM(s.amount), 0) FROM SavingsLedgerEntry s " +
           "WHERE s.userId = :userId GROUP BY s.savingsType")
    List<Object[]> savingsByType(@Param("userId") Long userId);

    @Query("SELECT s.productCategory, COALESCE(SUM(s.amount), 0) FROM SavingsLedgerEntry s " +
           "WHERE s.userId = :userId AND s.productCategory IS NOT NULL GROUP BY s.productCategory")
    List<Object[]> savingsByCategory(@Param("userId") Long userId);

    // ─── Retention aggregates ───────────────────────────────────────────────
    @Query("SELECT COALESCE(SUM(s.amount), 0) FROM SavingsLedgerEntry s")
    BigDecimal totalSavings();

    @Query("SELECT COUNT(DISTINCT s.userId) FROM SavingsLedgerEntry s")
    long distinctMembersWithSavings();
}
