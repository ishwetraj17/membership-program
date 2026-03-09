package com.firstclub.payments.dto;

import com.firstclub.payments.entity.RefundStatus;
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
public class RefundResponseDTO {

    private Long id;
    private Long paymentId;
    private BigDecimal amount;
    private String currency;
    private String reason;
    private RefundStatus status;
    private LocalDateTime createdAt;
}
