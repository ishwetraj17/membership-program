package com.firstclub.payments.dto;

import com.firstclub.payments.entity.PaymentMethodType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * Request body for registering a new tokenized payment method for a customer.
 *
 * <p><strong>Security note:</strong> This DTO must never carry a raw card PAN,
 * CVV, or full account number.  Only the opaque {@code providerToken} issued
 * by the payment gateway may be submitted.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMethodCreateRequestDTO {

    @NotNull(message = "methodType is required")
    private PaymentMethodType methodType;

    /**
     * Opaque token from the payment gateway / tokenization service.
     * <b>Not</b> a raw card number.
     */
    @NotBlank(message = "providerToken is required")
    @Size(max = 255)
    private String providerToken;

    /** Name of the payment provider (e.g. "razorpay", "stripe"). */
    @NotBlank(message = "provider is required")
    @Size(max = 64)
    private String provider;

    /** Stable fingerprint/hash for duplicate-detection. Optional. */
    @Size(max = 255)
    private String fingerprint;

    /** Last 4 digits or partial display identifier. Optional; display only. */
    @Size(max = 8, message = "last4 must be at most 8 characters")
    private String last4;

    /** Card scheme or wallet brand name. Optional; display only. */
    @Size(max = 64)
    private String brand;

    /** If {@code true}, this method becomes the customer's default on creation. */
    @Builder.Default
    private boolean makeDefault = false;
}
