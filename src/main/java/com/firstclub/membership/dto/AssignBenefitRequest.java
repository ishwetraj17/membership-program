package com.firstclub.membership.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Attach (or update) a benefit on a tier")
public class AssignBenefitRequest {

    @NotBlank(message = "Benefit code is required")
    @Schema(example = "FREE_DELIVERY", requiredMode = Schema.RequiredMode.REQUIRED)
    private String benefitCode;

    @Schema(description = "Optional value, e.g. '10%' or '5/month'", example = "5/month")
    private String value;
}
