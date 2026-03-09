package com.firstclub.ledger.revenue.repository;

import com.firstclub.ledger.revenue.entity.RevenueWaterfallProjection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RevenueWaterfallProjectionRepository
        extends JpaRepository<RevenueWaterfallProjection, Long> {

    /** Exact lookup for UPSERT logic. */
    Optional<RevenueWaterfallProjection> findByMerchantIdAndBusinessDate(
            Long merchantId, LocalDate businessDate);

    /** All waterfall rows for a merchant in a date range (inclusive). */
    List<RevenueWaterfallProjection> findByMerchantIdAndBusinessDateBetweenOrderByBusinessDateAsc(
            Long merchantId, LocalDate from, LocalDate to);

    /** All waterfall rows in a date range across all merchants. */
    List<RevenueWaterfallProjection> findByBusinessDateBetweenOrderByMerchantIdAscBusinessDateAsc(
            LocalDate from, LocalDate to);
}
