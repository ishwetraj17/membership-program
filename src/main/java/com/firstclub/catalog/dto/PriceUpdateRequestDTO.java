package com.firstclub.catalog.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for updating limited fields of an existing
 * {@link com.firstclub.catalog.entity.Price}.
 *
 * <p>The following fields are intentionally absent (immutable after creation):
 * {@code productId}, {@code priceCode}, {@code billingType}, {@code currency},
 * {@code amount}.  Amount changes must be made via a new
 * {@link PriceVersionCreateRequestDTO} to preserve history.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceUpdateRequestDTO {

    @Min(value = 0, message = "trialDays must be >= 0")
    private Integer trialDays;

    @Size(max = 10, message = "currency hint: field not mutable here")
    // reserved — not applied; surfaced so callers see a clear error rather than silent ignore
    private String currency;
}
