package com.firstclub.membership.dto;

import com.firstclub.membership.entity.IntroductoryOffer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Create a first-period introductory offer")
public class CreateIntroOfferRequest {

    @NotBlank
    @Schema(example = "FIRSTMONTH1", requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;

    @NotBlank
    private String description;

    @NotNull
    @Schema(example = "FIXED_PRICE", description = "FIXED_PRICE | PERCENT_OFF | FREE")
    private IntroductoryOffer.OfferType offerType;

    @PositiveOrZero
    @Schema(description = "FIXED_PRICE: first-period price; PERCENT_OFF: percent; FREE: omit", example = "1.00")
    private BigDecimal value;

    @Schema(description = "Restrict to one plan; omit for any plan", example = "2")
    private Long planId;
}
