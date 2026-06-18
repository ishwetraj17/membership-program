package com.firstclub.membership.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Start a free trial of a membership plan")
public class TrialRequest {

    @NotNull
    @Positive
    @Schema(example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userId;

    @NotNull
    @Positive
    @Schema(example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long planId;

    @NotNull
    @Schema(description = "Trial length in days — must be 7, 14 or 30", example = "14",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer trialDays;

    @Builder.Default
    @Schema(description = "Convert to paid automatically at trial end", example = "true", defaultValue = "true")
    private Boolean autoRenewal = true;
}
