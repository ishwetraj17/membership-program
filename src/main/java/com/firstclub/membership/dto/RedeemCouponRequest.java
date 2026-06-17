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
@Schema(description = "Redeem a coupon against an order amount")
public class RedeemCouponRequest {

    @NotNull
    @Positive
    private Long userId;

    @NotBlank
    private String code;

    @NotNull
    @PositiveOrZero
    private BigDecimal orderAmount;
}
