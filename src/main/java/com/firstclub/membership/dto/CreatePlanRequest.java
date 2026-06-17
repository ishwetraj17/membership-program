package com.firstclub.membership.dto;

import com.firstclub.membership.entity.MembershipPlan;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Create a new membership plan under a tier")
public class CreatePlanRequest {

    @NotBlank
    @Schema(example = "GOLD")
    private String tierName;

    @NotBlank
    private String name;

    @NotBlank
    private String description;

    @NotNull
    private MembershipPlan.PlanType type;

    @NotNull
    @Positive
    private BigDecimal price;

    @NotNull
    @Positive
    private Integer durationInMonths;
}
