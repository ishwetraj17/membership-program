package com.firstclub.membership.controller;

import com.firstclub.membership.config.AppConstants;
import com.firstclub.membership.dto.SubscriptionHistoryDTO;
import com.firstclub.membership.service.SubscriptionHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Read-only REST endpoints for the subscription audit trail.
 *
 * <p>Each endpoint requires either {@code ROLE_ADMIN} or verified ownership of
 * the subscription (enforced via {@code @securityService.isSubscriptionOwner}).
 *
 * Implemented by Shwet Raj
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/subscriptions/{subscriptionId}/history")
@RequiredArgsConstructor
@Validated
@Tag(name = "Subscription History", description = "Subscription audit trail — state changes, plan switches, cancellations")
public class SubscriptionHistoryController {

    private final SubscriptionHistoryService subscriptionHistoryService;

    @Operation(
        summary = "Get subscription history (paginated)",
        description = "Returns the audit trail for a subscription, newest entries first. " +
                      "Accessible to admins or the subscription owner.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "History retrieved successfully"),
        @ApiResponse(responseCode = "403", description = "Access denied"),
        @ApiResponse(responseCode = "404", description = "Subscription not found")
    })
    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or @securityService.isSubscriptionOwner(#subscriptionId, authentication)")
    public ResponseEntity<Page<SubscriptionHistoryDTO>> getHistory(
            @Parameter(description = "Subscription ID") @Positive @PathVariable Long subscriptionId,
            @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (max 100)") @Max(100) @RequestParam(defaultValue = "" + AppConstants.DEFAULT_PAGE_SIZE) int size) {

        log.info("Fetching history for subscription {}, page={}, size={}", subscriptionId, page, size);
        Page<SubscriptionHistoryDTO> history = subscriptionHistoryService
                .getHistoryBySubscriptionId(subscriptionId,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "changedAt")));
        return ResponseEntity.ok(history);
    }

    @Operation(
        summary = "Get full subscription history (unpaged)",
        description = "Returns the complete audit trail for a subscription, newest first. " +
                      "Use the paginated endpoint for large histories.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Full history retrieved successfully"),
        @ApiResponse(responseCode = "403", description = "Access denied"),
        @ApiResponse(responseCode = "404", description = "Subscription not found")
    })
    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN') or @securityService.isSubscriptionOwner(#subscriptionId, authentication)")
    public ResponseEntity<List<SubscriptionHistoryDTO>> getAllHistory(
            @Parameter(description = "Subscription ID") @Positive @PathVariable Long subscriptionId) {

        log.info("Fetching full history for subscription {}", subscriptionId);
        return ResponseEntity.ok(subscriptionHistoryService.getHistoryBySubscriptionId(subscriptionId));
    }
}
