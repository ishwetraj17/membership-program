package com.firstclub.payments.dto;

import com.firstclub.payments.entity.MandateStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * API response for a {@link com.firstclub.payments.entity.PaymentMethodMandate}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMethodMandateResponseDTO {

    private Long id;
    private Long paymentMethodId;

    private String mandateReference;
    private MandateStatus status;

    private BigDecimal maxAmount;
    private String currency;

    private LocalDateTime approvedAt;
    private LocalDateTime revokedAt;
    private LocalDateTime createdAt;
}
