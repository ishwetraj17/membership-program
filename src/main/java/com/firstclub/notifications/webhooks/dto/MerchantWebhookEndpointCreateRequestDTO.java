package com.firstclub.notifications.webhooks.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

/**
 * Request body for creating or updating a merchant webhook endpoint.
 *
 * <p>If {@code secret} is omitted (or blank), the server generates a
 * cryptographically-random 32-byte hex string automatically.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantWebhookEndpointCreateRequestDTO {

    /** HTTPS (or HTTP) URL that will receive event deliveries. */
    @NotBlank
    private String url;

    /**
     * Optional HMAC signing secret.  If blank, the server generates one.
     * On update, a blank value leaves the existing secret unchanged.
     */
    private String secret;

    /** Whether the endpoint should accept deliveries immediately. */
    @Builder.Default
    private boolean active = true;

    /**
     * JSON array of event types to receive, e.g. {@code ["invoice.paid","payment.failed"]}.
     * Use {@code ["*"]} to receive all events.
     */
    @NotBlank
    private String subscribedEventsJson;
}
