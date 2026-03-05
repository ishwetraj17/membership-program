package com.firstclub.membership.controller;

import com.firstclub.membership.dto.*;
import com.firstclub.membership.service.MembershipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/subscriptions")
@RequiredArgsConstructor
@Validated
@Tag(name = "Subscription Management", description = "APIs for managing user subscriptions")
public class SubscriptionController {

    private final MembershipService membershipService;

    @Operation(summary = "Get all subscriptions", description = "Admin-only: Retrieve all subscriptions with optional filtering and pagination")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved subscriptions"),
        @ApiResponse(responseCode = "403", description = "Access denied - admin role required"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<SubscriptionDTO>> getAllSubscriptions(
            @Parameter(description = "Filter by subscription status") @RequestParam(required = false) String status,
            @Parameter(description = "Filter by user ID") @RequestParam(required = false) Long userId,
            @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Sort by field") @RequestParam(defaultValue = "createdAt") String sort,
            @Parameter(description = "Sort direction") @RequestParam(defaultValue = "desc") String direction) {

        log.info("Getting all subscriptions - status: {}, userId: {}, page: {}, size: {}", status, userId, page, size);
        Sort.Direction sortDirection = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDirection, sort));
        Page<SubscriptionDTO> subscriptions = membershipService.getAllSubscriptionsPaged(pageable);
        log.info("Retrieved {} subscriptions (page {}/{})", subscriptions.getNumberOfElements(), page, subscriptions.getTotalPages());
        return ResponseEntity.ok(subscriptions);
    }

    @Operation(summary = "Create new subscription", description = "Create a new subscription for a user")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Subscription created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid subscription data"),
        @ApiResponse(responseCode = "404", description = "User or plan not found"),
        @ApiResponse(responseCode = "409", description = "User already has an active subscription"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PostMapping
    public ResponseEntity<SubscriptionDTO> createSubscription(
            @Parameter(description = "Subscription creation data") @Valid @RequestBody SubscriptionRequestDTO subscriptionRequest) {

        log.info("Creating new subscription for user ID: {}, plan ID: {}",
                subscriptionRequest.getUserId(), subscriptionRequest.getPlanId());
        SubscriptionDTO subscription = membershipService.createSubscription(subscriptionRequest);
        log.info("Created subscription with ID: {}", subscription.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(subscription);
    }

    @Operation(summary = "Get subscription by ID", description = "Retrieve a specific subscription by its ID")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Subscription found"),
        @ApiResponse(responseCode = "404", description = "Subscription not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/{id}")
    public ResponseEntity<SubscriptionDTO> getSubscriptionById(
            @Parameter(description = "Subscription ID") @Positive @PathVariable Long id) {

        log.info("Getting subscription by ID: {}", id);
        SubscriptionDTO subscription = membershipService.getSubscriptionById(id);
        return ResponseEntity.ok(subscription);
    }

    @Operation(summary = "Update subscription", description = "Update an existing subscription (plan change, auto-renewal, status)")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Subscription updated successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid update data or status transition"),
        @ApiResponse(responseCode = "404", description = "Subscription not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PutMapping("/{id}")
    public ResponseEntity<SubscriptionDTO> updateSubscription(
            @Parameter(description = "Subscription ID") @Positive @PathVariable Long id,
            @Parameter(description = "Subscription update data") @Valid @RequestBody SubscriptionUpdateDTO updateRequest) {

        log.info("Updating subscription ID: {}", id);
        SubscriptionDTO subscription = membershipService.updateSubscription(id, updateRequest);
        return ResponseEntity.ok(subscription);
    }

    @Operation(summary = "Cancel subscription", description = "Cancel an active subscription")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Subscription cancelled successfully"),
        @ApiResponse(responseCode = "400", description = "Cannot cancel subscription in current state"),
        @ApiResponse(responseCode = "404", description = "Subscription not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PutMapping("/{id}/cancel")
    public ResponseEntity<SubscriptionDTO> cancelSubscription(
            @Parameter(description = "Subscription ID") @Positive @PathVariable Long id,
            @Parameter(description = "Cancellation reason") @RequestParam(required = false) String reason) {

        log.info("Cancelling subscription ID: {} with reason: {}", id, reason);
        SubscriptionDTO subscription = membershipService.cancelSubscription(id, reason);
        return ResponseEntity.ok(subscription);
    }

    @Operation(summary = "Renew subscription", description = "Renew an expired subscription")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Subscription renewed successfully"),
        @ApiResponse(responseCode = "400", description = "Only expired subscriptions can be renewed"),
        @ApiResponse(responseCode = "404", description = "Subscription not found")
    })
    @PutMapping("/{id}/renew")
    public ResponseEntity<SubscriptionDTO> renewSubscription(
            @Parameter(description = "Subscription ID") @Positive @PathVariable Long id) {

        log.info("Renewing subscription ID: {}", id);
        SubscriptionDTO subscription = membershipService.renewSubscription(id);
        return ResponseEntity.ok(subscription);
    }

    @Operation(summary = "Preview upgrade cost", description = "Preview the cost difference before committing an upgrade")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Preview generated"),
        @ApiResponse(responseCode = "404", description = "Subscription or plan not found")
    })
    @GetMapping("/{id}/upgrade-preview")
    public ResponseEntity<UpgradePreviewDTO> getUpgradePreview(
            @Parameter(description = "Subscription ID") @Positive @PathVariable Long id,
            @Parameter(description = "Target plan ID") @RequestParam Long newPlanId) {

        log.info("Generating upgrade preview for subscription ID: {} to plan: {}", id, newPlanId);
        UpgradePreviewDTO preview = membershipService.getUpgradePreview(id, newPlanId);
        return ResponseEntity.ok(preview);
    }

    @PutMapping("/{id}/upgrade")
    public ResponseEntity<SubscriptionDTO> upgradeSubscription(
            @Parameter(description = "Subscription ID") @Positive @PathVariable Long id,
            @Parameter(description = "New plan details") @Valid @RequestBody PlanChangeRequestDTO request) {

        log.info("Upgrading subscription ID: {} to plan: {}", id, request.getNewPlanId());
        SubscriptionDTO subscription = membershipService.upgradeSubscription(id, request.getNewPlanId());
        return ResponseEntity.ok(subscription);
    }

    @Operation(summary = "Downgrade subscription", description = "Downgrade to a lower tier plan")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Subscription downgraded successfully"),
        @ApiResponse(responseCode = "400", description = "New plan must be of lower tier"),
        @ApiResponse(responseCode = "404", description = "Subscription or plan not found")
    })
    @PutMapping("/{id}/downgrade")
    public ResponseEntity<SubscriptionDTO> downgradeSubscription(
            @Parameter(description = "Subscription ID") @Positive @PathVariable Long id,
            @Parameter(description = "New plan details") @Valid @RequestBody PlanChangeRequestDTO request) {

        log.info("Downgrading subscription ID: {} to plan: {}", id, request.getNewPlanId());
        SubscriptionDTO subscription = membershipService.downgradeSubscription(id, request.getNewPlanId());
        return ResponseEntity.ok(subscription);
    }

    @Operation(summary = "Get subscriptions by user", description = "Get all subscriptions for a specific user with pagination")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved user subscriptions"),
        @ApiResponse(responseCode = "404", description = "User not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<SubscriptionDTO>> getSubscriptionsByUser(
            @Parameter(description = "User ID") @Positive @PathVariable Long userId,
            @Parameter(description = "Return only the active subscription") @RequestParam(defaultValue = "false") boolean activeOnly) {

        log.info("Getting subscriptions for user ID: {}, activeOnly: {}", userId, activeOnly);
        if (activeOnly) {
            return membershipService.getActiveSubscription(userId)
                    .map(s -> ResponseEntity.ok(List.of(s)))
                    .orElse(ResponseEntity.ok(List.of()));
        }
        List<SubscriptionDTO> subscriptions = membershipService.getUserSubscriptions(userId);
        log.info("Retrieved {} subscriptions for user: {}", subscriptions.size(), userId);
        return ResponseEntity.ok(subscriptions);
    }

    @Operation(summary = "Health check for subscription service", description = "Check subscription service health")
    @ApiResponse(responseCode = "200", description = "Service is healthy")
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = Map.of(
            "status", "UP",
            "service", "SubscriptionController",
            "timestamp", System.currentTimeMillis()
        );
        return ResponseEntity.ok(health);
    }
}
