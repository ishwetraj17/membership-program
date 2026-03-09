package com.firstclub.catalog.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Request body for creating a new {@link com.firstclub.catalog.entity.PriceVersion}.
 *
 * <p>A price version records a future or immediate change to the price's
 * amount/currency.  If {@code effectiveFrom} is in the past or now, the version
 * becomes the current version immediately.  If it is in the future, it is
 * scheduled and will be picked up by the billing engine when the time arrives.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceVersionCreateRequestDTO {

    @NotNull(message = "effectiveFrom is required")
    private LocalDateTime effectiveFrom;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.0001", message = "amount must be positive")
    private BigDecimal amount;

    @NotBlank(message = "currency is required")
    @Size(max = 10)
    private String currency;

    /**
     * When {@code true}, active subscriptions billing at the previous version's
     * rate are "grandfathered" — they continue at the old rate until they churn
     * or are explicitly migrated.  When {@code false} (default), active
     * subscriptions switch to the new rate at their next renewal.
     */
    @Builder.Default
    private boolean grandfatherExistingSubscriptions = false;
}
