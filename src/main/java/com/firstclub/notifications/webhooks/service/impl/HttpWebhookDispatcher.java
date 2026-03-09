package com.firstclub.notifications.webhooks.service.impl;

import com.firstclub.notifications.webhooks.service.WebhookDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Real HTTP implementation of {@link WebhookDispatcher} using {@link java.net.http.HttpClient}.
 *
 * <p>A new {@code HttpClient} is created per dispatcher instance (singleton) and reused
 * across calls for connection-pool efficiency.
 */
@Component
@Slf4j
public class HttpWebhookDispatcher implements WebhookDispatcher {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    @Override
    public int dispatch(String url, String payload, String signature, String eventType, Long deliveryId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Signature", signature)
                    .header("X-Event-Type", eventType)
                    .header("X-Delivery-Id", String.valueOf(deliveryId))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(TIMEOUT)
                    .build();

            int statusCode = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            log.debug("Webhook dispatch to {} returned HTTP {}", url, statusCode);
            return statusCode;
        } catch (Exception e) {
            log.warn("Webhook dispatch to {} failed: {}", url, e.getMessage());
            return -1;
        }
    }
}
