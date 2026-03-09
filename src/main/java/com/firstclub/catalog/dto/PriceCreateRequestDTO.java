package com.firstclub.catalog.dto;

import com.firstclub.catalog.entity.BillingIntervalUnit;
import com.firstclub.catalog.entity.BillingType;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request body for creating a new {@link com.firstclub.catalog.entity.Price}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceCreateRequestDTO {

    @NotNull(message = "productId is required")
    private Long productId;

    @NotBlank(message = "priceCode is required")
    @Size(max = 64, message = "priceCode must not exceed 64 characters")
    private String priceCode;

    @NotNull(message = "billingType is required")
    private BillingType billingType;

    @NotBlank(message = "currency is required")
    @Size(max = 10, message = "currency must not exceed 10 characters")
    private String currency;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.0001", message = "amount must be positive")
    private BigDecimal amount;

    /** Required when billingType is RECURRING; ignored for ONE_TIME. */
    private BillingIntervalUnit billingIntervalUnit;

    /** Must be ≥ 1 when billingType is RECURRING. */
    @Min(value = 1, message = "billingIntervalCount must be at least 1")
    private Integer billingIntervalCount;

    @Min(value = 0, message = "trialDays must be >= 0")
    @Builder.Default
    private int trialDays = 0;
}
