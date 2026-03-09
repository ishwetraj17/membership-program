package com.firstclub.reporting.ops.repository;

import com.firstclub.reporting.ops.entity.ReconDashboardProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface ReconDashboardProjectionRepository
        extends JpaRepository<ReconDashboardProjection, Long> {

    /** Platform-aggregate row (merchantId IS NULL) for a given date. */
    Optional<ReconDashboardProjection> findByMerchantIdIsNullAndBusinessDate(LocalDate businessDate);

    /** Merchant-scoped row for a given date. */
    Optional<ReconDashboardProjection> findByMerchantIdAndBusinessDate(Long merchantId, LocalDate businessDate);

    @Query("""
            SELECT r FROM ReconDashboardProjection r
            WHERE (:merchantId IS NULL OR r.merchantId = :merchantId)
            ORDER BY r.businessDate DESC
            """)
    Page<ReconDashboardProjection> findWithFilters(@Param("merchantId") Long merchantId, Pageable pageable);

    Page<ReconDashboardProjection> findByBusinessDateBetween(LocalDate from, LocalDate to, Pageable pageable);
}
