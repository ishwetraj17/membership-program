package com.firstclub.notifications.webhooks.service;

import com.firstclub.notifications.webhooks.dto.MerchantWebhookDeliveryResponseDTO;
import com.firstclub.notifications.webhooks.dto.MerchantWebhookPingResponseDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Handles the full outbound webhook delivery lifecycle:
 * enqueue → sign → dispatch → retry → terminal state.
 */
public interface MerchantWebhookDeliveryService {

    /**
     * Create a {@code PENDING} delivery record for every active endpoint of the merchant
     * that has subscribed to {@code eventType}.  The payload is signed immediately.
     *
     * <p>Auto-disabled endpoints (where {@code autoDisabledAt} is set) are skipped.
     * If a DELIVERED delivery with the same fingerprint already exists for an endpoint,
     * it is also skipped (idempotent enqueue guard).
     *
     * @param merchantId  merchant whose endpoints receive the event
     * @param eventType   event type string, e.g. {@code "invoice.paid"}
     * @param payloadJson full JSON string that will be POST-ed to each endpoint
     */
    void enqueueDeliveryForMerchantEvent(Long merchantId, String eventType, String payloadJson);

    /**
     * Sign a payload string with the given HMAC-SHA256 secret.
     *
     * @return signature in the format {@code sha256=<64 hex chars>}
     */
    String signPayload(String payload, String secret);

    /**
     * Scheduled entry point: find all deliveries that are PENDING or FAILED and
     * past their {@code nextAttemptAt} timestamp, then attempt to dispatch each.
     *
     * <p>Uses {@code FOR UPDATE SKIP LOCKED} for concurrency safety.
     */
    void retryDueDeliveries();

    /** Return all deliveries across all endpoints owned by the merchant, newest first. */
    List<MerchantWebhookDeliveryResponseDTO> listDeliveriesForMerchant(Long merchantId);

    /**
     * Return a single delivery, validating that the given merchant owns the endpoint.
     *
     * @throws com.firstclub.membership.exception.MembershipException with 404 if not found
     */
    MerchantWebhookDeliveryResponseDTO getDelivery(Long merchantId, Long deliveryId);

    /**
     * Send a synthetic {@code webhook.ping} event through the normal delivery mechanism.
     *
     * <p>Creates a delivery record, dispatches immediately (not via scheduler),
     * and returns the result.  Useful for testing endpoint reachability and
     * signature verification.
     *
     * @throws com.firstclub.membership.exception.MembershipException 404 if endpoint not found
     * @throws com.firstclub.membership.exception.MembershipException 422 if endpoint is inactive
     */
    MerchantWebhookPingResponseDTO pingEndpoint(Long merchantId, Long endpointId);

    /**
     * Filtered delivery search for a merchant.
     *
     * @param merchantId    tenant isolator
     * @param eventType     optional filter, e.g. {@code "invoice.paid"}
     * @param status        optional filter, e.g. {@code "DELIVERED"}
     * @param responseCode  optional filter, e.g. {@code 200}
     * @param from          optional lower bound (inclusive) on {@code createdAt}
     * @param to            optional upper bound (inclusive) on {@code createdAt}
     * @param limit         max results (≥ 1, capped at 500)
     */
    List<MerchantWebhookDeliveryResponseDTO> searchDeliveries(
            Long merchantId,
            String eventType,
            String status,
            Integer responseCode,
            LocalDateTime from,
            LocalDateTime to,
            int limit);
}
