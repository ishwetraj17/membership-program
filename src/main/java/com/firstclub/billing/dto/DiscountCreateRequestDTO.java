package com.firstclub.billing.dto;

import com.firstclub.billing.entity.DiscountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiscountCreateRequestDTO {

    @NotBlank
    private String code;

    @NotNull
    private DiscountType discountType;

    @NotNull
    @Positive
    private BigDecimal value;

    /** Required for FIXED discounts; ignored for PERCENTAGE. */
    private String currency;

    /** Null means unlimited total redemptions. */
    private Integer maxRedemptions;

    /** Null means unlimited redemptions per customer. */
    private Integer perCustomerLimit;

    @NotNull
    private LocalDateTime validFrom;

    @NotNull
    private LocalDateTime validTo;
}
