package com.firstclub.platform.ops.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Aggregated DLQ statistics for the ops dashboard.
 *
 * <ul>
 *   <li>{@code totalCount} — total rows in {@code dead_letter_messages}</li>
 *   <li>{@code bySource} — count per source label (OUTBOX / WEBHOOK)</li>
 *   <li>{@code byFailureCategory} — count per coarse failure category</li>
 * </ul>
 */
public record DlqSummaryDTO(
        long                  totalCount,
        Map<String, Long>     bySource,
        Map<String, Long>     byFailureCategory,
        LocalDateTime         reportedAt
) {}
