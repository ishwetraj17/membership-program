package com.firstclub.membership.dto;

import com.firstclub.membership.entity.Coupon;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Create a redeemable coupon")
public class CreateCouponRequest {

    @NotBlank
    @Schema(example = "WELCOME10")
    private String code;

    @NotBlank
    private String description;

    @NotNull
    private Coupon.DiscountType discountType;

    @NotNull
    @Positive
    private BigDecimal discountValue;

    @Schema(description = "Total redemptions allowed (null = unlimited)")
    private Integer maxRedemptions;

    @Schema(description = "Redemptions per user (null = unlimited)")
    private Integer perUserLimit;

    private LocalDateTime expiresAt;
}
