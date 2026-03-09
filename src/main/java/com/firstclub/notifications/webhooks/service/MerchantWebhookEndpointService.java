package com.firstclub.notifications.webhooks.service;

import com.firstclub.notifications.webhooks.dto.MerchantWebhookEndpointCreateRequestDTO;
import com.firstclub.notifications.webhooks.dto.MerchantWebhookEndpointResponseDTO;

import java.util.List;

/**
 * Manages the lifecycle of merchant webhook endpoint registrations.
 */
public interface MerchantWebhookEndpointService {

    /** Register a new webhook endpoint for the given merchant. */
    MerchantWebhookEndpointResponseDTO createEndpoint(Long merchantId,
            MerchantWebhookEndpointCreateRequestDTO request);

    /** Return all endpoints (active and inactive) for this merchant. */
    List<MerchantWebhookEndpointResponseDTO> listEndpoints(Long merchantId);

    /**
     * Update an existing endpoint's URL, active flag, subscribed events,
     * and optionally its secret (blank value leaves the secret unchanged).
     */
    MerchantWebhookEndpointResponseDTO updateEndpoint(Long merchantId, Long endpointId,
            MerchantWebhookEndpointCreateRequestDTO request);

    /** Deactivate (soft-delete) an endpoint. Non-destructive: delivery history is kept. */
    void deactivateEndpoint(Long merchantId, Long endpointId);

    /**
     * Re-enable a previously auto-disabled or manually deactivated endpoint.
     *
     * <p>Resets: {@code active = true}, {@code autoDisabledAt = null},
     * {@code consecutiveFailures = 0}.
     *
     * @throws com.firstclub.membership.exception.MembershipException 404 if not found
     */
    void reenableEndpoint(Long merchantId, Long endpointId);
}
