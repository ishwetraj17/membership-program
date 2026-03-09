package com.firstclub.notifications.webhooks.controller;

import com.firstclub.notifications.webhooks.dto.MerchantWebhookEndpointCreateRequestDTO;
import com.firstclub.notifications.webhooks.dto.MerchantWebhookEndpointResponseDTO;
import com.firstclub.notifications.webhooks.dto.MerchantWebhookPingResponseDTO;
import com.firstclub.notifications.webhooks.service.MerchantWebhookDeliveryService;
import com.firstclub.notifications.webhooks.service.MerchantWebhookEndpointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CRUD management for a merchant's outbound webhook endpoint registrations.
 */
@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/webhook-endpoints")
@RequiredArgsConstructor
@Tag(name = "Merchant Webhook Endpoints",
     description = "Register and manage URLs that receive signed event notifications")
public class MerchantWebhookEndpointController {

    private final MerchantWebhookEndpointService endpointService;
    private final MerchantWebhookDeliveryService  deliveryService;

    @Operation(summary = "Register a new webhook endpoint")
    @PostMapping
    public ResponseEntity<MerchantWebhookEndpointResponseDTO> create(
            @PathVariable Long merchantId,
            @Valid @RequestBody MerchantWebhookEndpointCreateRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(endpointService.createEndpoint(merchantId, request));
    }

    @Operation(summary = "List all webhook endpoints for this merchant")
    @GetMapping
    public ResponseEntity<List<MerchantWebhookEndpointResponseDTO>> list(
            @PathVariable Long merchantId) {
        return ResponseEntity.ok(endpointService.listEndpoints(merchantId));
    }

    @Operation(summary = "Update an existing webhook endpoint")
    @PutMapping("/{endpointId}")
    public ResponseEntity<MerchantWebhookEndpointResponseDTO> update(
            @PathVariable Long merchantId,
            @PathVariable Long endpointId,
            @Valid @RequestBody MerchantWebhookEndpointCreateRequestDTO request) {
        return ResponseEntity.ok(endpointService.updateEndpoint(merchantId, endpointId, request));
    }

    @Operation(summary = "Deactivate a webhook endpoint (soft-delete)")
    @DeleteMapping("/{endpointId}")
    public ResponseEntity<Void> deactivate(
            @PathVariable Long merchantId,
            @PathVariable Long endpointId) {
        endpointService.deactivateEndpoint(merchantId, endpointId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Re-enable a previously deactivated or auto-disabled endpoint",
               description = "Resets active=true, autoDisabledAt=null, consecutiveFailures=0.")
    @PatchMapping("/{endpointId}/reenable")
    public ResponseEntity<Void> reenable(
            @PathVariable Long merchantId,
            @PathVariable Long endpointId) {
        endpointService.reenableEndpoint(merchantId, endpointId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Send a test ping event to the endpoint",
               description = "Dispatches a synthetic 'webhook.ping' event immediately and returns the delivery result.")
    @PostMapping("/{endpointId}/ping")
    public ResponseEntity<MerchantWebhookPingResponseDTO> ping(
            @PathVariable Long merchantId,
            @PathVariable Long endpointId) {
        return ResponseEntity.ok(deliveryService.pingEndpoint(merchantId, endpointId));
    }
}
