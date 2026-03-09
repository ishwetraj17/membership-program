package com.firstclub.notifications.webhooks.dto;

import com.firstclub.notifications.webhooks.entity.MerchantWebhookDeliveryStatus;
import lombok.*;

import java.time.LocalDateTime;

/** Read-only representation of a single webhook delivery record. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantWebhookDeliveryResponseDTO {

    private Long   id;
    private Long   endpointId;
    private String eventType;

    /** Full JSON payload sent (or to be sent) to the endpoint. */
    private String payload;

    /** HMAC-SHA256 signature in the format {@code sha256=<hex>}. */
    private String signature;

    private MerchantWebhookDeliveryStatus status;
    private int    attemptCount;

    /** HTTP status code returned by the last attempt, or null if never dispatched. */
    private Integer lastResponseCode;
    private String  lastError;

    /** When the next retry will fire (null for terminal states). */
    private LocalDateTime nextAttemptAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
