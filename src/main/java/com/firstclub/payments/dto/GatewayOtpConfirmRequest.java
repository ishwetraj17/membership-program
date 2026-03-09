package com.firstclub.payments.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for POST /gateway/otp/confirm (fake gateway emulator).
 *
 * @param paymentIntentId  ID of the PaymentIntent that completed 3-DS / OTP.
 */
public record GatewayOtpConfirmRequest(

        @NotNull(message = "paymentIntentId is required")
        Long paymentIntentId
) {}
