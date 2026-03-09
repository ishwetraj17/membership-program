package com.firstclub.payments.disputes.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Request body for opening a new dispute against a payment.
 * {@code paymentId} and {@code merchantId} come from the URL path — not required here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisputeCreateRequestDTO {

    /** The customer who raised the dispute. */
    @NotNull
    private Long customerId;

    /** Amount being disputed. Must be &gt; 0 and ≤ (capturedAmount - refundedAmount - existingDisputedAmount). */
    @NotNull
    @Positive
    private BigDecimal amount;

    /** Structured reason, e.g. FRAUDULENT_CHARGE, PRODUCT_NOT_RECEIVED, SUBSCRIPTION_CANCELLED. */
    @NotBlank
    @Size(max = 64)
    private String reasonCode;

    /** Optional evidence submission deadline. Null means no deadline enforced. */
    private LocalDateTime dueBy;
}
