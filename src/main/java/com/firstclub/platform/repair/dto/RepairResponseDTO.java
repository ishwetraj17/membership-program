package com.firstclub.platform.repair.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Response payload returned for every repair action execution.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RepairResponseDTO {

    String       repairKey;
    boolean      success;
    boolean      dryRun;
    String       details;
    String       errorMessage;
    String       beforeSnapshotJson;
    String       afterSnapshotJson;
    Long         auditId;
    LocalDateTime executedAt;

    public static RepairResponseDTO of(com.firstclub.platform.repair.RepairActionResult result, Long auditId) {
        return RepairResponseDTO.builder()
                .repairKey(result.getRepairKey())
                .success(result.isSuccess())
                .dryRun(result.isDryRun())
                .details(result.getDetails())
                .errorMessage(result.getErrorMessage())
                .beforeSnapshotJson(result.getBeforeSnapshotJson())
                .afterSnapshotJson(result.getAfterSnapshotJson())
                .auditId(auditId)
                .executedAt(result.getEvaluatedAt())
                .build();
    }
}
