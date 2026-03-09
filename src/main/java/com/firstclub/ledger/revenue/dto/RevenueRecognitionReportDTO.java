package com.firstclub.ledger.revenue.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Summary of recognition activity within a date range ({@code GET /report?from=&to=}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevenueRecognitionReportDTO {

    private LocalDate from;
    private LocalDate to;

    /** Total amount of revenue successfully posted to the ledger. */
    private BigDecimal postedAmount;

    /** Total amount scheduled but not yet posted (future or overdue). */
    private BigDecimal pendingAmount;

    /** Total amount that failed posting and awaits review/retry. */
    private BigDecimal failedAmount;

    private int postedCount;
    private int pendingCount;
    private int failedCount;
}
