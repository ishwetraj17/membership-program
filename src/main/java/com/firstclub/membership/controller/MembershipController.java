package com.firstclub.membership.controller;

import com.firstclub.membership.dto.*;
import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.service.MembershipService;
import com.firstclub.membership.service.PlanService;
import com.firstclub.membership.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/membership")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Membership Management", description = "Plans, tiers, subscriptions and analytics")
public class MembershipController {

    private final MembershipService membershipService;
    private final PlanService planService;
    private final SubscriptionService subscriptionService;

    // ─── Plans ────────────────────────────────────────────────────────────────

    @GetMapping("/plans")
    @Operation(summary = "Get all active plans")
    @ApiResponse(responseCode = "200", description = "Plans retrieved")
    public ResponseEntity<List<MembershipPlanDTO>> getAllPlans() {
        return ResponseEntity.ok(planService.getActivePlans());
    }

    @GetMapping("/plans/tier/{tierName}")
    @Operation(summary = "Get plans by tier name")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Plans retrieved"),
        @ApiResponse(responseCode = "404", description = "Tier not found")
    })
    public ResponseEntity<List<MembershipPlanDTO>> getPlansByTier(
            @Parameter(example = "GOLD") @PathVariable String tierName) {
        return ResponseEntity.ok(planService.getPlansByTier(tierName));
    }

    @GetMapping("/plans/tier-id/{tierId}")
    @Operation(summary = "Get plans by tier ID")
    public ResponseEntity<List<MembershipPlanDTO>> getPlansByTierId(@PathVariable Long tierId) {
        return ResponseEntity.ok(planService.getPlansByTierId(tierId));
    }

    @GetMapping("/plans/type/{type}")
    @Operation(summary = "Get plans by duration type")
    public ResponseEntity<List<MembershipPlanDTO>> getPlansByType(
            @Parameter(example = "YEARLY") @PathVariable MembershipPlan.PlanType type) {
        return ResponseEntity.ok(planService.getPlansByType(type));
    }

    @GetMapping("/plans/{id}")
    @Operation(summary = "Get plan by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Plan found"),
        @ApiResponse(responseCode = "404", description = "Plan not found")
    })
    public ResponseEntity<MembershipPlanDTO> getPlanById(@PathVariable Long id) {
        return ResponseEntity.ok(planService.getPlanById(id)
                .orElseThrow(() -> MembershipException.planNotFound(id)));
    }

    // ─── Tiers ────────────────────────────────────────────────────────────────

    @GetMapping("/tiers")
    @Operation(summary = "Get all membership tiers")
    public ResponseEntity<List<TierDTO>> getAllTiers() {
        return ResponseEntity.ok(membershipService.getAllTiers());
    }

    @GetMapping("/tiers/{name}")
    @Operation(summary = "Get tier by name")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Tier found"),
        @ApiResponse(responseCode = "404", description = "Tier not found")
    })
    public ResponseEntity<TierDTO> getTierByName(
            @Parameter(example = "PLATINUM") @PathVariable String name) {
        return ResponseEntity.ok(membershipService.getTierByName(name)
                .orElseThrow(() -> MembershipException.tierNotFound(name)));
    }

    @GetMapping("/tiers/id/{id}")
    @Operation(summary = "Get tier by ID")
    public ResponseEntity<TierDTO> getTierById(@PathVariable Long id) {
        return ResponseEntity.ok(membershipService.getTierById(id)
                .orElseThrow(() -> MembershipException.tierNotFound("ID: " + id)));
    }

    // ─── Subscriptions ────────────────────────────────────────────────────────

    @PostMapping("/subscriptions")
    @Operation(summary = "Create subscription")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Subscription created"),
        @ApiResponse(responseCode = "400", description = "Invalid request"),
        @ApiResponse(responseCode = "409", description = "User already has active subscription")
    })
    public ResponseEntity<SubscriptionDTO> createSubscription(@Valid @RequestBody SubscriptionRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(subscriptionService.createSubscription(request));
    }

    @GetMapping("/subscriptions")
    @Operation(summary = "Get all subscriptions — paginated (admin)")
    public ResponseEntity<Page<SubscriptionDTO>> getAllSubscriptions(
            @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return ResponseEntity.ok(subscriptionService.getAllSubscriptions(pageable));
    }

    @GetMapping("/subscriptions/user/{userId}")
    @Operation(summary = "Get all subscriptions for a user")
    public ResponseEntity<List<SubscriptionDTO>> getUserSubscriptions(@PathVariable Long userId) {
        return ResponseEntity.ok(subscriptionService.getUserSubscriptions(userId));
    }

    @GetMapping("/subscriptions/user/{userId}/active")
    @Operation(summary = "Get user's active subscription")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Active subscription found"),
        @ApiResponse(responseCode = "404", description = "No active subscription")
    })
    public ResponseEntity<SubscriptionDTO> getActiveSubscription(@PathVariable Long userId) {
        return ResponseEntity.ok(subscriptionService.getActiveSubscription(userId)
                .orElseThrow(() -> new MembershipException(
                        "No active subscription for user " + userId,
                        "NO_ACTIVE_SUBSCRIPTION",
                        org.springframework.http.HttpStatus.NOT_FOUND)));
    }

    @PutMapping("/subscriptions/{id}")
    @Operation(summary = "Update subscription settings")
    public ResponseEntity<SubscriptionDTO> updateSubscription(
            @PathVariable Long id,
            @RequestBody SubscriptionUpdateDTO updateDTO) {
        return ResponseEntity.ok(subscriptionService.updateSubscription(id, updateDTO));
    }

    @PutMapping("/subscriptions/{id}/cancel")
    @Operation(summary = "Cancel active subscription")
    public ResponseEntity<SubscriptionDTO> cancelSubscription(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("reason", "User requested cancellation")
                                     : "User requested cancellation";
        return ResponseEntity.ok(subscriptionService.cancelSubscription(id, reason));
    }

    @PutMapping("/subscriptions/{id}/renew")
    @Operation(summary = "Renew expired subscription")
    public ResponseEntity<SubscriptionDTO> renewSubscription(@PathVariable Long id) {
        return ResponseEntity.ok(subscriptionService.renewSubscription(id));
    }

    @PutMapping("/subscriptions/{id}/upgrade")
    @Operation(summary = "Upgrade subscription to higher tier")
    public ResponseEntity<SubscriptionDTO> upgradeSubscription(
            @PathVariable Long id,
            @Valid @RequestBody UpgradeRequest request) {
        return ResponseEntity.ok(subscriptionService.upgradeSubscription(id, request.getNewPlanId()));
    }

    @PutMapping("/subscriptions/{id}/downgrade")
    @Operation(summary = "Downgrade subscription to lower tier")
    public ResponseEntity<SubscriptionDTO> downgradeSubscription(
            @PathVariable Long id,
            @Valid @RequestBody UpgradeRequest request) {
        return ResponseEntity.ok(subscriptionService.downgradeSubscription(id, request.getNewPlanId()));
    }

    // ─── Observability ────────────────────────────────────────────────────────

    @GetMapping("/health")
    @Operation(summary = "System health and key metrics")
    public ResponseEntity<Map<String, Object>> getSystemHealth() {
        Map<String, Object> health = new HashMap<>();
        try {
            Map<String, Object> stats = subscriptionService.getActiveStats();

            health.put("status", "UP");
            health.put("timestamp", LocalDateTime.now());
            health.put("metrics", Map.of(
                "totalUsers", stats.get("uniqueUsers"),
                "activeSubscriptions", stats.get("activeSubscriptions"),
                "availablePlans", planService.getActivePlans().size(),
                "membershipTiers", membershipService.getAllTiers().size(),
                "tierDistribution", stats.get("tierDistribution")
            ));
            health.put("system", Map.of(
                "javaVersion", System.getProperty("java.version"),
                "database", "PostgreSQL"
            ));
            return ResponseEntity.ok(health);
        } catch (Exception e) {
            health.put("status", "DOWN");
            health.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(health);
        }
    }

    @GetMapping("/analytics")
    @Operation(summary = "Business analytics")
    public ResponseEntity<Map<String, Object>> getAnalytics() {
        Map<String, Object> stats = subscriptionService.getAnalyticsStats();

        Map<String, Object> analytics = new HashMap<>();
        analytics.put("revenue", Map.of(
            "totalRevenue", stats.get("totalRevenue"),
            "currency", "INR",
            "averageRevenuePerUser", stats.get("averageRevenuePerUser")
        ));
        analytics.put("membership", Map.of(
            "tierPopularity", stats.get("tierDistribution"),
            "planTypeDistribution", stats.get("planTypeDistribution"),
            "totalActivePlans", planService.getActivePlans().size()
        ));
        analytics.put("summary", Map.of(
            "totalSubscriptions", stats.get("totalSubscriptions"),
            "activeSubscriptions", stats.get("activeSubscriptions"),
            "generatedAt", LocalDateTime.now()
        ));
        return ResponseEntity.ok(analytics);
    }
}
