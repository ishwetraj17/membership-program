package com.firstclub.subscription.controller;

import com.firstclub.subscription.dto.SubscriptionScheduleCreateRequestDTO;
import com.firstclub.subscription.dto.SubscriptionScheduleResponseDTO;
import com.firstclub.subscription.service.SubscriptionScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for subscription schedules.
 *
 * <p>Nested under the subscription resource:
 * {@code /api/v2/merchants/{merchantId}/subscriptions/{subscriptionId}/schedules}
 */
@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/subscriptions/{subscriptionId}/schedules")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Subscription Schedules", description = "Future scheduled actions for subscriptions")
public class SubscriptionScheduleController {

    private final SubscriptionScheduleService scheduleService;

    @PostMapping
    @Operation(summary = "Schedule a future action on a subscription")
    public ResponseEntity<SubscriptionScheduleResponseDTO> create(
            @PathVariable Long merchantId,
            @PathVariable Long subscriptionId,
            @Valid @RequestBody SubscriptionScheduleCreateRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(scheduleService.createSchedule(merchantId, subscriptionId, request));
    }

    @GetMapping
    @Operation(summary = "List all schedules for a subscription")
    public ResponseEntity<List<SubscriptionScheduleResponseDTO>> list(
            @PathVariable Long merchantId,
            @PathVariable Long subscriptionId) {
        return ResponseEntity.ok(scheduleService.listSchedulesForSubscription(merchantId, subscriptionId));
    }

    @DeleteMapping("/{scheduleId}")
    @Operation(summary = "Cancel a scheduled action")
    public ResponseEntity<SubscriptionScheduleResponseDTO> cancel(
            @PathVariable Long merchantId,
            @PathVariable Long subscriptionId,
            @PathVariable Long scheduleId) {
        return ResponseEntity.ok(scheduleService.cancelSchedule(merchantId, subscriptionId, scheduleId));
    }
}
