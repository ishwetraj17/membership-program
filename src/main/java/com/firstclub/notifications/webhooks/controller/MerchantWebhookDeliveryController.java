package com.firstclub.notifications.webhooks.controller;

import com.firstclub.notifications.webhooks.dto.MerchantWebhookDeliveryResponseDTO;
import com.firstclub.notifications.webhooks.service.MerchantWebhookDeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-only visibility into outbound webhook delivery records for a merchant.
 */
@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/webhook-deliveries")
@RequiredArgsConstructor
@Tag(name = "Merchant Webhook Deliveries",
     description = "Inspect outbound webhook delivery history and status")
public class MerchantWebhookDeliveryController {

    private final MerchantWebhookDeliveryService deliveryService;

    @Operation(summary = "List all webhook deliveries for this merchant (newest first)")
    @GetMapping
    public ResponseEntity<List<MerchantWebhookDeliveryResponseDTO>> list(
            @PathVariable Long merchantId) {
        return ResponseEntity.ok(deliveryService.listDeliveriesForMerchant(merchantId));
    }

    @Operation(summary = "Get details of a single webhook delivery")
    @GetMapping("/{deliveryId}")
    public ResponseEntity<MerchantWebhookDeliveryResponseDTO> get(
            @PathVariable Long merchantId,
            @PathVariable Long deliveryId) {
        return ResponseEntity.ok(deliveryService.getDelivery(merchantId, deliveryId));
    }

    @Operation(summary = "Search webhook deliveries with filters",
               description = "Supports filtering by eventType, status (PENDING/FAILED/DELIVERED/GAVE_UP), "
                           + "HTTP responseCode, and createdAt date range. Max 500 results.")
    @GetMapping("/search")
    public ResponseEntity<List<MerchantWebhookDeliveryResponseDTO>> search(
            @PathVariable Long merchantId,
            @RequestParam(required = false)                                               String        eventType,
            @RequestParam(required = false)                                               String        status,
            @RequestParam(required = false)                                               Integer       responseCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "50")                                            int           limit) {
        return ResponseEntity.ok(
                deliveryService.searchDeliveries(merchantId, eventType, status, responseCode, from, to, limit));
    }
}
