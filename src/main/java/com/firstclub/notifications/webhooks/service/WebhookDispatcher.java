package com.firstclub.notifications.webhooks.service;

/**
 * Port for dispatching a single signed HTTP POST to a merchant's webhook URL.
 *
 * <p>Separating this from the delivery service makes the retry and scheduling
 * logic fully unit-testable without needing a live HTTP server.
 */
public interface WebhookDispatcher {

    /**
     * POST {@code payload} to {@code url} with the given headers.
     *
     * @return HTTP status code on success, {@code -1} on connection / IO failure
     */
    int dispatch(String url, String payload, String signature, String eventType, Long deliveryId);
}
