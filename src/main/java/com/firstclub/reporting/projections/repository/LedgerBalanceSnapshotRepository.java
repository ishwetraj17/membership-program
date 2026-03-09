package com.firstclub.reporting.projections.repository;

import com.firstclub.reporting.projections.entity.LedgerBalanceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LedgerBalanceSnapshotRepository extends JpaRepository<LedgerBalanceSnapshot, Long> {

    /** Used for idempotency check: platform-wide (merchant_id IS NULL) snapshot. */
    boolean existsByAccountIdAndSnapshotDateAndMerchantIdIsNull(Long accountId, LocalDate snapshotDate);

    /** Retrieve existing platform-wide snapshot for a given account + date. */
    Optional<LedgerBalanceSnapshot> findByAccountIdAndSnapshotDateAndMerchantIdIsNull(Long accountId, LocalDate snapshotDate);

    /** Flexible filter query for the admin list endpoint. */
    @Query("""
            SELECT s FROM LedgerBalanceSnapshot s
            WHERE (:merchantId IS NULL OR s.merchantId = :merchantId)
              AND (:from       IS NULL OR s.snapshotDate >= :from)
              AND (:to         IS NULL OR s.snapshotDate <= :to)
            ORDER BY s.snapshotDate ASC, s.accountId ASC
            """)
    List<LedgerBalanceSnapshot> findWithFilters(
            @Param("merchantId") Long merchantId,
            @Param("from")       LocalDate from,
            @Param("to")         LocalDate to);
}
