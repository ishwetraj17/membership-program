package com.firstclub.payments.controller;

import com.firstclub.payments.service.WebhookIngestResult;
import com.firstclub.payments.service.WebhookProcessingService;
import com.firstclub.platform.ratelimit.RateLimitPolicy;
import com.firstclub.platform.ratelimit.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Receives signed webhook events from the payment gateway.
 *
 * <p>The gateway places a lowercase hex-encoded HMAC-SHA256 signature in the
 * {@code X-Signature} header.  Events with an invalid signature are stored
 * (for audit) but never processed.
 *
 * <p>The endpoint is idempotent: re-delivering an already-processed event_id
 * returns 200 OK without re-processing.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Webhooks", description = "Inbound signed webhook callbacks from the payment gateway")
public class WebhookController {

    private final WebhookProcessingService webhookProcessingService;

    @Operation(summary = "Receive a gateway webhook",
               description = "Validates HMAC-SHA256 signature (X-Signature header) and processes the event.")
    @RateLimit(RateLimitPolicy.WEBHOOK_INGEST)
    @PostMapping("/gateway")
    public ResponseEntity<Map<String, String>> receive(
            @RequestBody String payload,
            @RequestHeader(value = "X-Signature", defaultValue = "") String signature) {

        log.debug("Received webhook, signature present={}", !signature.isBlank());
        WebhookIngestResult result = webhookProcessingService.ingestWebhookEvent(payload, signature);

        return switch (result) {
            case PROCESSED, DUPLICATE -> ResponseEntity.ok(Map.of("status", "OK", "result", result.name()));
            case INVALID_SIGNATURE    -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "ERROR", "result", "INVALID_SIGNATURE"));
            case ERROR                -> ResponseEntity.internalServerError()
                    .body(Map.of("status", "ERROR", "result", "PROCESSING_ERROR"));
        };
    }
}
