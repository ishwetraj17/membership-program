package com.firstclub.platform.ops.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Summary of outstanding work in the outbox.
 *
 * <ul>
 *   <li>totalPending         = newCount + processingCount + failedCount</li>
 *   <li>byEventType          = active event counts grouped by event type name</li>
 *   <li>staleLeasesCount     = events stuck in PROCESSING beyond the lease threshold</li>
 *   <li>oldestPendingAgeSeconds = age in seconds of the oldest NEW or PROCESSING event</li>
 * </ul>
 */
public record OutboxLagResponseDTO(
        long                  newCount,
        long                  processingCount,
        long                  failedCount,
        long                  doneCount,
        long                  totalPending,
        Map<String, Long>     byEventType,
        long                  staleLeasesCount,
        Long                  oldestPendingAgeSeconds,
        LocalDateTime         reportedAt
) {}
