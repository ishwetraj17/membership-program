package com.firstclub.platform.integrity.dto;

import java.time.LocalDateTime;

/**
 * Response payload representing one integrity check finding within a run.
 */
public record IntegrityFindingResponseDTO(
        Long id,
        Long runId,
        String invariantKey,
        String severity,
        String status,
        int violationCount,
        String detailsJson,
        String suggestedRepairKey,
        LocalDateTime createdAt
) {

    /** Convenience factory from entity. */
    public static IntegrityFindingResponseDTO of(
            com.firstclub.platform.integrity.entity.IntegrityCheckFinding finding) {
        return new IntegrityFindingResponseDTO(
                finding.getId(),
                finding.getRunId(),
                finding.getInvariantKey(),
                finding.getSeverity(),
                finding.getStatus(),
                finding.getViolationCount(),
                finding.getDetailsJson(),
                finding.getSuggestedRepairKey(),
                finding.getCreatedAt()
        );
    }
}
