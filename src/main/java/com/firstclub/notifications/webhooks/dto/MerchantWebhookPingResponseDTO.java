package com.firstclub.notifications.webhooks.dto;

import lombok.*;

/**
 * Result of a synthetic webhook ping request.
 *
 * <p>A ping triggers an immediate signed delivery to the endpoint.
 * The {@code status} reflects the terminal state of that delivery attempt
 * ({@code DELIVERED} or {@code FAILED}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantWebhookPingResponseDTO {

    /** ID of the delivery record created for this ping. */
    private Long   deliveryId;

    /** The endpoint that was pinged. */
    private Long   endpointId;

    /** Terminal status of the ping delivery: {@code DELIVERED} or {@code FAILED}. */
    private String status;

    /** Human-readable outcome message. */
    private String message;
}
