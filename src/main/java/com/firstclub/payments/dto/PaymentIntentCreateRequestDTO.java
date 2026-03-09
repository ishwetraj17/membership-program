package com.firstclub.payments.dto;

import com.firstclub.payments.entity.CaptureMode;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Request body for creating a new payment intent.
 *
 * <p>The caller MUST supply an {@code Idempotency-Key} HTTP header.
 * If an intent with the same key and {@code merchantId} already exists,
 * the existing intent is returned without side effects.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentIntentCreateRequestDTO {

    @NotNull
    private Long customerId;

    /** The invoice this payment will fulfil (optional). */
    private Long invoiceId;

    /** The subscription this payment belongs to (optional). */
    private Long subscriptionId;

    @NotNull
    @DecimalMin(value = "0.0001", message = "amount must be greater than zero")
    private BigDecimal amount;

    @NotBlank
    @Size(max = 10)
    private String currency;

    /** Pre-attach a payment method on creation (optional). */
    private Long paymentMethodId;

    @NotNull
    @Builder.Default
    private CaptureMode captureMode = CaptureMode.AUTO;

    /** Free-form JSON metadata stored against this intent. */
    private String metadataJson;
}
