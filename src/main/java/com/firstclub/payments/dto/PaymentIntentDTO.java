package com.firstclub.payments.dto;

import com.firstclub.payments.model.PaymentIntentStatus;
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
public class PaymentIntentDTO {

    private Long id;
    private Long invoiceId;
    private BigDecimal amount;
    private String currency;
    private PaymentIntentStatus status;
    private String clientSecret;
    private String gatewayReference;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
