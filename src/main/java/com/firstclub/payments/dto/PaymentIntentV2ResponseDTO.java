package com.firstclub.payments.dto;

import com.firstclub.payments.entity.CaptureMode;
import com.firstclub.payments.entity.PaymentIntentStatusV2;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * API response for a {@link com.firstclub.payments.entity.PaymentIntentV2}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentIntentV2ResponseDTO {

    private Long id;
    private Long merchantId;
    private Long customerId;

    private Long invoiceId;
    private Long subscriptionId;
    private Long paymentMethodId;

    private BigDecimal amount;
    private String currency;

    private PaymentIntentStatusV2 status;
    private CaptureMode captureMode;

    private String clientSecret;
    private String idempotencyKey;
    private String metadataJson;

    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
