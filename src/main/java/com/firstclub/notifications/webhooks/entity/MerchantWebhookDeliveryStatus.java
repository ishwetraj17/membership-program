package com.firstclub.notifications.webhooks.entity;

/**
 * Lifecycle status of a single outbound webhook delivery attempt.
 */
public enum MerchantWebhookDeliveryStatus {

    /** Created, not yet dispatched. */
    PENDING,

    /** Last HTTP call returned 2xx — no further retries needed. */
    DELIVERED,

    /** Last HTTP call failed; a retry is scheduled. */
    FAILED,

    /** All retry attempts exhausted; endpoint may be auto-disabled. */
    GAVE_UP
}
