package com.firstclub.billing.dto;

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
public class CreditNoteDTO {

    private Long id;
    private Long userId;
    private String currency;
    private BigDecimal amount;
    private String reason;
    private LocalDateTime createdAt;
    private BigDecimal usedAmount;
    private BigDecimal availableBalance;
}
