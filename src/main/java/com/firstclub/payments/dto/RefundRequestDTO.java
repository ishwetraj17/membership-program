package com.firstclub.payments.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundRequestDTO {

    @NotNull
    private Long paymentId;

    @NotNull
    @Positive
    private BigDecimal amount;

    private String reason;
}
