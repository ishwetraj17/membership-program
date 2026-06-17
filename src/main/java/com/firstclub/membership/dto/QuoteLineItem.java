package com.firstclub.membership.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A line item in a checkout cart")
public class QuoteLineItem {

    @NotBlank
    private String name;

    @Schema(description = "Item category (for category-targeted benefits)", example = "ELECTRONICS")
    private String category;

    @NotNull
    @PositiveOrZero
    private BigDecimal unitPrice;

    @Positive
    private int quantity;
}
