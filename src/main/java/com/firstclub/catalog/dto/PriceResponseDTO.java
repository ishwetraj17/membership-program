package com.firstclub.catalog.dto;

import com.firstclub.catalog.entity.BillingIntervalUnit;
import com.firstclub.catalog.entity.BillingType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response payload for a {@link com.firstclub.catalog.entity.Price}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceResponseDTO {

    private Long id;
    private Long merchantId;
    private Long productId;
    private String priceCode;
    private BillingType billingType;
    private String currency;
    private BigDecimal amount;
    private BillingIntervalUnit billingIntervalUnit;
    private int billingIntervalCount;
    private int trialDays;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
