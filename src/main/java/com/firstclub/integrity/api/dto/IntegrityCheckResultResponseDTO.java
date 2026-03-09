package com.firstclub.integrity.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Per-invariant result record included in {@link IntegrityCheckRunResponseDTO}.
 */
@Value
@Builder
public class IntegrityCheckResultResponseDTO {

    @JsonProperty("invariant_name")
    String invariantName;

    @JsonProperty("status")
    String status;

    @JsonProperty("violation_count")
    int violationCount;

    @JsonProperty("severity")
    String severity;

    @JsonProperty("affected_entities")
    List<String> affectedEntities;

    @JsonProperty("suggested_repair_action")
    String suggestedRepairAction;
}
