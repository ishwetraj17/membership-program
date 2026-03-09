package com.firstclub.platform.scheduler.health;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.firstclub.platform.scheduler.entity.SchedulerExecutionHistory;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Response DTO for {@code GET /ops/schedulers/history} — a single execution
 * record from {@link SchedulerExecutionHistory}.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SchedulerHistoryResponseDTO {

    Long id;
    String schedulerName;
    String nodeId;
    Instant startedAt;
    Instant completedAt;
    String status;
    Integer processedCount;
    String errorMessage;
    Instant createdAt;

    public static SchedulerHistoryResponseDTO from(SchedulerExecutionHistory entity) {
        return SchedulerHistoryResponseDTO.builder()
                .id(entity.getId())
                .schedulerName(entity.getSchedulerName())
                .nodeId(entity.getNodeId())
                .startedAt(entity.getStartedAt())
                .completedAt(entity.getCompletedAt())
                .status(entity.getStatus())
                .processedCount(entity.getProcessedCount())
                .errorMessage(entity.getErrorMessage())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
