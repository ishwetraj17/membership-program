package com.firstclub.membership.dto;

import com.firstclub.membership.entity.BenefitType;
import com.firstclub.membership.entity.ProductCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Admin payload to create or update a configurable benefit rule.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Create/update a configurable commerce benefit rule for a tier")
public class BenefitRuleRequest {

    @NotNull
    @Schema(example = "2", requiredMode = Schema.RequiredMode.REQUIRED, description = "Tier this rule applies to")
    private Long tierId;

    @NotNull
    @Schema(example = "PERCENTAGE_DISCOUNT", requiredMode = Schema.RequiredMode.REQUIRED)
    private BenefitType benefitType;

    @Schema(description = "Restrict a discount to one product category; null = whole cart", example = "BEAUTY")
    private ProductCategory productCategory;

    @PositiveOrZero
    @Schema(description = "Threshold the qualifying base must reach; null = always applies", example = "199.00")
    private BigDecimal minCartValue;

    @DecimalMin(value = "0.0", inclusive = false)
    @DecimalMax(value = "100.0")
    @Schema(description = "Discount percent (required for PERCENTAGE_DISCOUNT)", example = "10.00")
    private BigDecimal discountPercentage;

    @PositiveOrZero
    @Schema(description = "Optional cap on the discount amount", example = "150.00")
    private BigDecimal maxDiscountAmount;

    @Schema(description = "Tie-breaker when rules overlap (higher first)", example = "0")
    private Integer priority;

    @Schema(description = "Whether the rule is active", example = "true")
    private Boolean active;
}
