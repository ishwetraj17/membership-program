package com.firstclub.catalog.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response payload for a {@link com.firstclub.catalog.entity.PriceVersion}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceVersionResponseDTO {

    private Long id;
    private Long priceId;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private BigDecimal amount;
    private String currency;
    private boolean grandfatherExistingSubscriptions;
    private LocalDateTime createdAt;
}
