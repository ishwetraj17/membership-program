package com.firstclub.payments.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for POST /gateway/pay (fake gateway emulator).
 *
 * @param paymentIntentId  ID of the PaymentIntent to charge.
 * @param outcome          Simulated gateway outcome for this charge.
 */
public record GatewayPayRequest(

        @NotNull(message = "paymentIntentId is required")
        Long paymentIntentId,

        @NotBlank(message = "outcome is required")
        @Pattern(regexp = "SUCCEEDED|FAILED|REQUIRES_ACTION",
                 message = "outcome must be SUCCEEDED, FAILED or REQUIRES_ACTION")
        String outcome
) {}
