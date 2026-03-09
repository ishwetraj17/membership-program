package com.firstclub.billing.dto;

import com.firstclub.billing.entity.DiscountStatus;
import com.firstclub.billing.entity.DiscountType;
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
public class DiscountResponseDTO {

    private Long id;
    private Long merchantId;
    private String code;
    private DiscountType discountType;
    private BigDecimal value;
    private String currency;
    private Integer maxRedemptions;
    private Integer perCustomerLimit;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private DiscountStatus status;
    private LocalDateTime createdAt;

    /** Total number of times this discount has been redeemed. */
    private long redemptionCount;
}
