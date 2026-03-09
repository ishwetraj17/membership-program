package com.firstclub.dunning.controller;

import com.firstclub.dunning.dto.SubscriptionPaymentPreferenceRequestDTO;
import com.firstclub.dunning.dto.SubscriptionPaymentPreferenceResponseDTO;
import com.firstclub.dunning.entity.DunningAttempt;
import com.firstclub.dunning.service.DunningServiceV2;
import com.firstclub.dunning.service.SubscriptionPaymentPreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Subscription-scoped endpoints for payment preferences and dunning attempts.
 *
 * <p>All paths are prefixed with
 * {@code /api/v2/merchants/{merchantId}/subscriptions/{subscriptionId}}.
 */
@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/subscriptions/{subscriptionId}")
@RequiredArgsConstructor
@Tag(name = "Subscription Payment Preferences (v2)",
     description = "Configure primary/backup payment methods and inspect dunning attempts per subscription")
public class SubscriptionPaymentPreferenceController {

    private final SubscriptionPaymentPreferenceService preferenceService;
    private final DunningServiceV2                     dunningServiceV2;

    @PutMapping("/payment-preferences")
    @Operation(
        summary = "Set payment preferences for a subscription",
        description = "Creates or replaces the payment method configuration for a subscription. "
                + "Both primary and backup instruments must belong to the subscription's customer "
                + "within the merchant scope.")
    public ResponseEntity<SubscriptionPaymentPreferenceResponseDTO> setPreferences(
            @Parameter(description = "Merchant identifier", required = true)
            @PathVariable Long merchantId,
            @Parameter(description = "Subscription identifier", required = true)
            @PathVariable Long subscriptionId,
            @Valid @RequestBody SubscriptionPaymentPreferenceRequestDTO request) {
        return ResponseEntity.ok(
                preferenceService.setPaymentPreferences(merchantId, subscriptionId, request));
    }

    @GetMapping("/payment-preferences")
    @Operation(summary = "Get payment preferences for a subscription")
    public ResponseEntity<SubscriptionPaymentPreferenceResponseDTO> getPreferences(
            @PathVariable Long merchantId,
            @PathVariable Long subscriptionId) {
        return ResponseEntity.ok(
                preferenceService.getPreferencesForSubscription(merchantId, subscriptionId));
    }

    @GetMapping("/dunning-attempts")
    @Operation(
        summary = "List dunning attempts for a subscription",
        description = "Returns all v1 and v2 dunning attempts ordered by creation time. "
                + "Validates that the subscription belongs to the merchant before returning data.")
    public ResponseEntity<List<DunningAttempt>> getDunningAttempts(
            @PathVariable Long merchantId,
            @PathVariable Long subscriptionId) {
        return ResponseEntity.ok(
                dunningServiceV2.getAttemptsForSubscription(merchantId, subscriptionId));
    }
}
