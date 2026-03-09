package com.firstclub.risk.dto;

import com.firstclub.risk.entity.ReviewCaseStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManualReviewResolveRequestDTO {

    /** Target status for the case. Valid values: APPROVED, REJECTED, ESCALATED, CLOSED. */
    @NotNull(message = "resolution is required")
    private ReviewCaseStatus resolution;

    /** Optional free-text note explaining the decision. */
    private String note;
}
