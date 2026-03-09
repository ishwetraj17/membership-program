package com.firstclub.ledger.revenue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Read-only view of one row in {@code revenue_waterfall_projection}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RevenueWaterfallProjectionDTO {

    private Long id;
    private Long merchantId;
    private LocalDate businessDate;

    /** Total revenue billed (invoices PAID) on this day. */
    private BigDecimal billedAmount;

    /** Deferred revenue balance at opening of day. */
    private BigDecimal deferredOpening;

    /** Deferred revenue balance at close of day. */
    private BigDecimal deferredClosing;

    /** Revenue recognised (schedules POSTED) on this day. */
    private BigDecimal recognizedAmount;

    /** Refunds applied on this day. */
    private BigDecimal refundedAmount;

    /** Disputes opened on this day. */
    private BigDecimal disputedAmount;

    private LocalDateTime updatedAt;
}
