package com.firstclub.reporting.projections.repository;

import com.firstclub.reporting.projections.entity.LedgerBalanceProjection;
import com.firstclub.reporting.projections.entity.LedgerBalanceProjectionId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Optional;

public interface LedgerBalanceProjectionRepository
        extends JpaRepository<LedgerBalanceProjection, LedgerBalanceProjectionId> {

    Page<LedgerBalanceProjection> findByMerchantId(Long merchantId, Pageable pageable);

    Optional<LedgerBalanceProjection> findByMerchantIdAndUserId(Long merchantId, Long userId);

    @Query("SELECT MIN(p.updatedAt) FROM LedgerBalanceProjection p")
    Optional<LocalDateTime> findOldestUpdatedAt();
}
