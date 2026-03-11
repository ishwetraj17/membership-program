package com.firstclub.reporting.projections.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Carries staleness information for a single named projection.
 * Returned by {@link com.firstclub.reporting.projections.service.ProjectionLagMonitor}.
 */
@Data
@Builder
public class ProjectionLagReport {

    /** Internal name of the projection (e.g. {@code customer_payment_summary}). */
    private String projectionName;

    /** Timestamp of the oldest row in the projection table; null if empty. */
    private LocalDateTime oldestUpdatedAt;

    /** Lag in seconds between {@code oldestUpdatedAt} and now; -1 if table is empty. */
    private long lagSeconds;

    /**
     * True if {@link #lagSeconds} exceeds the configured staleness threshold.
     *
     * @param thresholdSeconds maximum acceptable lag in seconds
     */
    public boolean isStale(long thresholdSeconds) {
        return lagSeconds >= 0 && lagSeconds > thresholdSeconds;
    }
}
