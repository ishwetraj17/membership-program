package com.firstclub.reporting.projections.repository;

import com.firstclub.reporting.projections.entity.MerchantDailyKpiProjection;
import com.firstclub.reporting.projections.entity.MerchantKpiProjectionId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

public interface MerchantDailyKpiProjectionRepository
        extends JpaRepository<MerchantDailyKpiProjection, MerchantKpiProjectionId> {

    Optional<MerchantDailyKpiProjection> findByMerchantIdAndBusinessDate(Long merchantId, LocalDate businessDate);

    Page<MerchantDailyKpiProjection> findByMerchantId(Long merchantId, Pageable pageable);

    Page<MerchantDailyKpiProjection> findByMerchantIdAndBusinessDateBetween(
            Long merchantId, LocalDate from, LocalDate to, Pageable pageable);

    Page<MerchantDailyKpiProjection> findByBusinessDateBetween(LocalDate from, LocalDate to, Pageable pageable);

    /** @deprecated Use {@link #findByMerchantIdAndBusinessDateBetween} with Pageable sort instead. */
    @Deprecated
    Page<MerchantDailyKpiProjection> findByMerchantIdAndBusinessDateBetweenOrderByBusinessDateAsc(
            Long merchantId, LocalDate from, LocalDate to, Pageable pageable);

    /** @deprecated Use {@link #findByBusinessDateBetween} with Pageable sort instead. */
    @Deprecated
    Page<MerchantDailyKpiProjection> findByBusinessDateBetweenOrderByMerchantIdAscBusinessDateAsc(
            LocalDate from, LocalDate to, Pageable pageable);

    @Query("SELECT MIN(p.updatedAt) FROM MerchantDailyKpiProjection p")
    Optional<LocalDateTime> findOldestUpdatedAt();
}
