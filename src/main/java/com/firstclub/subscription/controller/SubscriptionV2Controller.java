package com.firstclub.subscription.controller;

import com.firstclub.subscription.dto.SubscriptionCreateRequestDTO;
import com.firstclub.subscription.dto.SubscriptionResponseDTO;
import com.firstclub.subscription.entity.SubscriptionStatusV2;
import com.firstclub.subscription.service.SubscriptionV2Service;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for subscription contracts (v2).
 *
 * <p>All endpoints are tenant-scoped via {@code merchantId} path variable.
 * Requires {@code ADMIN} role.
 */
@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/subscriptions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Subscriptions V2", description = "Subscription contract management")
public class SubscriptionV2Controller {

    private final SubscriptionV2Service subscriptionService;

    @PostMapping
    @Operation(summary = "Create a new subscription")
    public ResponseEntity<SubscriptionResponseDTO> create(
            @PathVariable Long merchantId,
            @Valid @RequestBody SubscriptionCreateRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(subscriptionService.createSubscription(merchantId, request));
    }

    @GetMapping
    @Operation(summary = "List subscriptions for a merchant")
    public ResponseEntity<Page<SubscriptionResponseDTO>> list(
            @PathVariable Long merchantId,
            @RequestParam(required = false) SubscriptionStatusV2 status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(subscriptionService.listSubscriptions(merchantId, status, pageable));
    }

    @GetMapping("/{subscriptionId}")
    @Operation(summary = "Get a subscription by ID")
    public ResponseEntity<SubscriptionResponseDTO> get(
            @PathVariable Long merchantId,
            @PathVariable Long subscriptionId) {
        return ResponseEntity.ok(subscriptionService.getSubscriptionById(merchantId, subscriptionId));
    }

    @PostMapping("/{subscriptionId}/cancel")
    @Operation(summary = "Cancel a subscription (immediate or at period end)")
    public ResponseEntity<SubscriptionResponseDTO> cancel(
            @PathVariable Long merchantId,
            @PathVariable Long subscriptionId,
            @RequestParam(defaultValue = "false") boolean atPeriodEnd) {
        return ResponseEntity.ok(subscriptionService.cancelSubscription(merchantId, subscriptionId, atPeriodEnd));
    }

    @PostMapping("/{subscriptionId}/pause")
    @Operation(summary = "Pause an active subscription")
    public ResponseEntity<SubscriptionResponseDTO> pause(
            @PathVariable Long merchantId,
            @PathVariable Long subscriptionId) {
        return ResponseEntity.ok(subscriptionService.pauseSubscription(merchantId, subscriptionId));
    }

    @PostMapping("/{subscriptionId}/resume")
    @Operation(summary = "Resume a paused subscription")
    public ResponseEntity<SubscriptionResponseDTO> resume(
            @PathVariable Long merchantId,
            @PathVariable Long subscriptionId) {
        return ResponseEntity.ok(subscriptionService.resumeSubscription(merchantId, subscriptionId));
    }
}
