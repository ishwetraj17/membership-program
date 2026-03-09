package com.firstclub.recon.repository;

import com.firstclub.recon.entity.SettlementBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, Long> {

    Page<SettlementBatch> findByMerchantId(Long merchantId, Pageable pageable);

    Optional<SettlementBatch> findByMerchantIdAndBatchDate(Long merchantId, LocalDate batchDate);

    // ── Phase 14: Duplicate settlement detection ──────────────────────────────

    /**
     * Returns one {@link DuplicateBatchProjection} per {@code (merchant_id, batch_date)}
     * combination where more than one settlement batch row exists on {@code date}.
     *
     * <p>Any result here indicates a critical data-integrity defect: the same
     * set of transactions may have been settled (and paid out) twice.
     */
    @Query("SELECT b.merchantId AS merchantId, COUNT(b) AS batchCount " +
           "FROM SettlementBatch b " +
           "WHERE b.batchDate = :date " +
           "GROUP BY b.merchantId " +
           "HAVING COUNT(b) > 1")
    List<DuplicateBatchProjection> findDuplicateMerchantBatchesForDate(@Param("date") LocalDate date);
}

