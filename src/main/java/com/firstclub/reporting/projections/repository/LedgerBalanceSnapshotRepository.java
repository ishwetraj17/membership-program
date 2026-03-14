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
    @Query(value = """
            SELECT * FROM ledger_balance_snapshots s
            WHERE (CAST(:merchantId AS BIGINT) IS NULL OR s.merchant_id = CAST(:merchantId AS BIGINT))
              AND (CAST(:from AS DATE)         IS NULL OR s.snapshot_date >= CAST(:from AS DATE))
              AND (CAST(:to AS DATE)           IS NULL OR s.snapshot_date <= CAST(:to AS DATE))
            ORDER BY s.snapshot_date ASC, s.account_id ASC
            """, nativeQuery = true)
    List<LedgerBalanceSnapshot> findWithFilters(
            @Param("merchantId") Long merchantId,
            @Param("from")       LocalDate from,
            @Param("to")         LocalDate to);
}
