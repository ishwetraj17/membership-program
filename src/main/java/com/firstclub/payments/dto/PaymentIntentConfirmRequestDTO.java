package com.firstclub.payments.dto;

import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * Request body for confirming a payment intent.
 *
 * <p>If {@code paymentMethodId} is supplied it will be attached (or replaced)
 * before dispatching.  If the intent already has a payment method and none is
 * provided here, the existing method is used.
 *
 * <p>The caller MUST supply an {@code Idempotency-Key} HTTP header.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentIntentConfirmRequestDTO {

    /** Override or attach a payment method at confirmation time. */
    private Long paymentMethodId;

    /**
     * Optional gateway hint. When omitted, the routing service selects the best gateway
     * automatically. Providing a value serves as a fallback if no routing rules are configured.
     */
    @Size(max = 64)
    private String gatewayName;

    /** Optional metadata to attach to this specific attempt. */
    private String attemptMetadata;
}
