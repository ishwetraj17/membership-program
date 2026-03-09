package com.firstclub.platform.ops.dto;

import java.time.LocalDateTime;

/**
 * Aggregated system health snapshot for the deep-health admin endpoint.
 *
 * <p>overallStatus values:
 * <ul>
 *   <li>HEALTHY  — all indicators nominal</li>
 *   <li>DEGRADED — at least one backlog / failure counter is non-zero</li>
 *   <li>DOWN     — database unreachable</li>
 * </ul>
 */
public record DeepHealthResponseDTO(
        String        overallStatus,
        boolean       dbReachable,
        long          outboxPendingCount,
        long          outboxFailedCount,
        long          dlqCount,
        long          webhookPendingCount,
        long          webhookFailedCount,
        long          revRecogFailedCount,
        long          reconMismatchOpenCount,
        long          dunningBacklogCount,
        long          integrityViolationCount,
        String        integrityLastRunStatus,
        long          featureFlagCount,
        String        ledgerStatus,
        String        redisStatus,
        long          rateLimitBlocksLastHour,
        LocalDateTime checkedAt
) {}
