package com.firstclub.integrity.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Top-level response returned by the integrity-check admin endpoints.
 */
@Value
@Builder
public class IntegrityCheckRunResponseDTO {

    Long id;

    @JsonProperty("started_at")
    LocalDateTime startedAt;

    @JsonProperty("completed_at")
    LocalDateTime completedAt;

    String status;

    @JsonProperty("triggered_by")
    String triggeredBy;

    @JsonProperty("request_id")
    String requestId;

    @JsonProperty("correlation_id")
    String correlationId;

    @JsonProperty("total_checkers")
    int totalCheckers;

    @JsonProperty("failed_checkers")
    int failedCheckers;

    @JsonProperty("error_checkers")
    int errorCheckers;

    List<IntegrityCheckResultResponseDTO> results;
}
