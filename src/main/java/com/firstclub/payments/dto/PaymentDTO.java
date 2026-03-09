package com.firstclub.payments.dto;

import com.firstclub.payments.entity.PaymentStatus;
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
public class PaymentDTO {

    private Long id;
    private Long paymentIntentId;
    private BigDecimal amount;
    private String currency;
    private PaymentStatus status;
    private String gatewayTxnId;
    private LocalDateTime capturedAt;
    private LocalDateTime createdAt;
}
