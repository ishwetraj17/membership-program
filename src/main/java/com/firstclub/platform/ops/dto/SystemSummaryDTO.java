package com.firstclub.platform.ops.dto;

import java.time.LocalDateTime;

/**
 * Point-in-time operational summary aggregating all major backlog counters
 * and the last integrity-run status.
 *
 * <p>All counts reflect the live state of the operational tables at the moment
 * this snapshot was taken; no caching is applied by the service layer.
 * Designed to be scraped by dashboards and on-call tooling.
 *
 * <h3>Staleness note</h3>
 * The snapshot is generated synchronously during the HTTP request.
 * If the underlying queries are slow (e.g., full-table count on a large
 * {@code dunning_attempts} table), consider caching the response at the
 * load-balancer layer with a short TTL (15–30 s).
 */
public record SystemSummaryDTO(
        long          outboxPendingCount,
        long          outboxFailedCount,
        long          dlqCount,
        long          webhookPendingCount,
        long          webhookFailedCount,
        long          reconMismatchOpenCount,
        long          dunningBacklogCount,
        long          staleJobLockCount,
        long          integrityViolationCount,
        String        integrityLastRunStatus,
        LocalDateTime integrityLastRunAt,
        LocalDateTime generatedAt
) {}
