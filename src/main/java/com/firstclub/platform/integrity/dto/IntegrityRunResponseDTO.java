package com.firstclub.platform.integrity.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response payload representing one integrity check run.
 *
 * <p>{@code findings} is populated only for single-run detail
 * ({@code GET /runs/{runId}}); it is {@code null} in list responses.
 */
public record IntegrityRunResponseDTO(
        Long id,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String status,
        int totalChecks,
        int failedChecks,
        String summaryJson,
        Long merchantId,
        String invariantKey,
        List<IntegrityFindingResponseDTO> findings
) {

    /** Convenience factory from entity — without findings (list view). */
    public static IntegrityRunResponseDTO of(
            com.firstclub.platform.integrity.entity.IntegrityCheckRun run) {
        return new IntegrityRunResponseDTO(
                run.getId(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getStatus(),
                run.getTotalChecks(),
                run.getFailedChecks(),
                run.getSummaryJson(),
                run.getMerchantId(),
                run.getInvariantKey(),
                null
        );
    }

    /** Convenience factory from entity — with findings (detail view). */
    public static IntegrityRunResponseDTO withFindings(
            com.firstclub.platform.integrity.entity.IntegrityCheckRun run,
            List<IntegrityFindingResponseDTO> findings) {
        return new IntegrityRunResponseDTO(
                run.getId(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getStatus(),
                run.getTotalChecks(),
                run.getFailedChecks(),
                run.getSummaryJson(),
                run.getMerchantId(),
                run.getInvariantKey(),
                findings
        );
    }
}
