package com.firstclub.ledger.revenue.service;

import com.firstclub.ledger.revenue.dto.RevenueWaterfallProjectionDTO;

import java.time.LocalDate;
import java.util.List;

/**
 * Maintains the {@code revenue_waterfall_projection} table.
 *
 * <p>The waterfall is updated after each recognition run (or on-demand via
 * the admin API) to reflect how much revenue was billed, deferred, and
 * recognised on each calendar day per merchant.
 */
public interface RevenueWaterfallProjectionService {

    /**
     * Computes and upserts the waterfall row for the given merchant and date.
     *
     * <p>{@code recognized_amount} is derived from POSTED schedule rows.
     * Other amounts ({@code billed_amount}, {@code deferred_*}, {@code refunded_amount},
     * {@code disputed_amount}) are reserved for future phases and default to 0.
     *
     * @param merchantId merchant scope
     * @param date       the business date to compute
     * @return the upserted projection DTO
     */
    RevenueWaterfallProjectionDTO updateProjectionForDate(Long merchantId, LocalDate date);

    /**
     * Returns waterfall rows for a merchant between {@code from} and {@code to}
     * (both inclusive).
     *
     * @param merchantId merchant scope
     * @param from       start date (inclusive)
     * @param to         end date (inclusive)
     * @return list ordered by {@code business_date} ascending
     */
    List<RevenueWaterfallProjectionDTO> getWaterfall(Long merchantId, LocalDate from, LocalDate to);

    /**
     * Returns waterfall rows across all merchants between {@code from} and {@code to}.
     *
     * @param from start date (inclusive)
     * @param to   end date (inclusive)
     * @return list ordered by merchantId asc, business_date asc
     */
    List<RevenueWaterfallProjectionDTO> getWaterfallAllMerchants(LocalDate from, LocalDate to);
}
