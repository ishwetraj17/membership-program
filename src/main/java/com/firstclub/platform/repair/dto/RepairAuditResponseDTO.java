package com.firstclub.platform.repair.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.firstclub.platform.repair.entity.RepairActionAudit;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Audit log entry returned by {@code GET /api/v2/admin/repair/audit}.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RepairAuditResponseDTO {

    Long          id;
    String        repairKey;
    String        targetType;
    String        targetId;
    Long          actorUserId;
    String        reason;
    String        status;
    boolean       dryRun;
    LocalDateTime createdAt;

    public static RepairAuditResponseDTO of(RepairActionAudit audit) {
        return RepairAuditResponseDTO.builder()
                .id(audit.getId())
                .repairKey(audit.getRepairKey())
                .targetType(audit.getTargetType())
                .targetId(audit.getTargetId())
                .actorUserId(audit.getActorUserId())
                .reason(audit.getReason())
                .status(audit.getStatus())
                .dryRun(audit.isDryRun())
                .createdAt(audit.getCreatedAt())
                .build();
    }
}
