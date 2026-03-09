package com.firstclub.ledger.revenue.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Summary of a single recognition run ({@code POST /run?date=...}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevenueRecognitionRunResponseDTO {

    /** The date for which recognition was processed. */
    private String date;

    /** Total PENDING schedules found on/before this date. */
    private int scheduled;

    /** Number successfully posted to ledger. */
    private int posted;

    /** Number that failed posting and remain PENDING for retry. */
    private int failed;

    /** IDs of schedules that failed (can be retried or investigated). */
    private List<Long> failedScheduleIds;
}
