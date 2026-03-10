package com.firstclub.billing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** DTO for the PUT/POST apply-credit endpoint body. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyCreditRequestDTO {

    /** Amount to apply from the credit wallet. Must be positive and <= invoice grand total. */
    private BigDecimal amountToApply;
}
