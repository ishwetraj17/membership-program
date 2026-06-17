package com.firstclub.membership.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request a price quote for a cart, applying the user's membership benefits")
public class CheckoutQuoteRequest {

    @NotNull
    @Positive
    @Schema(example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userId;

    @NotEmpty
    @Valid
    private List<QuoteLineItem> items;

    @Schema(description = "Standard delivery fee before membership benefits", example = "49.00")
    private BigDecimal deliveryFee;

    @Schema(description = "Optional coupon code to preview", example = "WELCOME10")
    private String couponCode;
}
