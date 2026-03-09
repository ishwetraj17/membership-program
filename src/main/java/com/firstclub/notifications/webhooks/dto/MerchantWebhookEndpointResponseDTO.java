package com.firstclub.notifications.webhooks.dto;

import lombok.*;

import java.time.LocalDateTime;

/**
 * Read-only representation of a webhook endpoint returned by the API.
 *
 * <p>The {@code secret} is intentionally omitted for security.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantWebhookEndpointResponseDTO {

    private Long   id;
    private Long   merchantId;
    private String url;
    private boolean active;

    /** JSON array of subscribed event types. */
    private String subscribedEventsJson;

    /** Running count of consecutive failed dispatch attempts (reset to 0 on any success). */
    private int consecutiveFailures;

    /**
     * When the system auto-disabled this endpoint, or {@code null} if it has
     * never been auto-disabled.
     */
    private LocalDateTime autoDisabledAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
