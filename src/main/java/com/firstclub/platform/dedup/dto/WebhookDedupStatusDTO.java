package com.firstclub.platform.dedup.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Webhook dedup status response for the admin diagnostic endpoint.
 */
@Value
@Builder
public class WebhookDedupStatusDTO {

    String  provider;
    String  eventId;
    /** Whether a dedup marker exists in Redis for this provider + eventId. */
    boolean redisMarkerPresent;
    /** Whether the webhook_events row for this eventId has been processed. */
    boolean dbProcessed;
    /** Overall status: NEW | DUPLICATE */
    String  status;
}
