package com.firstclub.membership.controller;

import com.firstclub.membership.dto.*;
import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.service.MembershipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/membership")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Membership Management", description = "Plans, tiers, subscriptions and analytics")
public class MembershipController {

    private final MembershipService membershipService;

    // ─── Plans ────────────────────────────────────────────────────────────────

    @GetMapping("/plans")
    @Operation(summary = "Get all active plans")
    @ApiResponse(responseCode = "200", description = "Plans retrieved")
    public ResponseEntity<List<MembershipPlanDTO>> getAllPlans() {
        return ResponseEntity.ok(membershipService.getActivePlans());
    }

    @GetMapping("/plans/tier/{tierName}")
    @Operation(summary = "Get plans by tier name")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Plans retrieved"),
        @ApiResponse(responseCode = "404", description = "Tier not found")
    })
    public ResponseEntity<List<MembershipPlanDTO>> getPlansByTier(
            @Parameter(example = "GOLD") @PathVariable String tierName) {
        return ResponseEntity.ok(membershipService.getPlansByTier(tierName));
    }

    @GetMapping("/plans/tier-id/{tierId}")
    @Operation(summary = "Get plans by tier ID")
    public ResponseEntity<List<MembershipPlanDTO>> getPlansByTierId(@PathVariable Long tierId) {
        return ResponseEntity.ok(membershipService.getPlansByTierId(tierId));
    }

    @GetMapping("/plans/type/{type}")
    @Operation(summary = "Get plans by duration type")
    public ResponseEntity<List<MembershipPlanDTO>> getPlansByType(
            @Parameter(example = "YEARLY") @PathVariable MembershipPlan.PlanType type) {
        return ResponseEntity.ok(membershipService.getPlansByType(type));
    }

    @GetMapping("/plans/{id}")
    @Operation(summary = "Get plan by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Plan found"),
        @ApiResponse(responseCode = "404", description = "Plan not found")
    })
    public ResponseEntity<MembershipPlanDTO> getPlanById(@PathVariable Long id) {
        return membershipService.getPlanById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ─── Tiers ───────────────────────────────────────────────────────────────

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
        return membershipService.getTierByName(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/tiers/id/{id}")
    @Operation(summary = "Get tier by ID")
    public ResponseEntity<TierDTO> getTierById(@PathVariable Long id) {
        return membershipService.getTierById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
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
        return ResponseEntity.status(HttpStatus.CREATED).body(membershipService.createSubscription(request));
    }

    @GetMapping("/subscriptions")
    @Operation(summary = "Get all subscriptions (admin)")
    public ResponseEntity<List<SubscriptionDTO>> getAllSubscriptions() {
        return ResponseEntity.ok(membershipService.getAllSubscriptions());
    }

    @GetMapping("/subscriptions/user/{userId}")
    @Operation(summary = "Get all subscriptions for a user")
    public ResponseEntity<List<SubscriptionDTO>> getUserSubscriptions(@PathVariable Long userId) {
        return ResponseEntity.ok(membershipService.getUserSubscriptions(userId));
    }

    @GetMapping("/subscriptions/user/{userId}/active")
    @Operation(summary = "Get user's active subscription")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Active subscription found"),
        @ApiResponse(responseCode = "404", description = "No active subscription")
    })
    public ResponseEntity<SubscriptionDTO> getActiveSubscription(@PathVariable Long userId) {
        return membershipService.getActiveSubscription(userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/subscriptions/{id}")
    @Operation(summary = "Update subscription settings")
    public ResponseEntity<SubscriptionDTO> updateSubscription(
            @PathVariable Long id,
            @RequestBody SubscriptionUpdateDTO updateDTO) {
        return ResponseEntity.ok(membershipService.updateSubscription(id, updateDTO));
    }

    @PutMapping("/subscriptions/{id}/cancel")
    @Operation(summary = "Cancel active subscription")
    public ResponseEntity<SubscriptionDTO> cancelSubscription(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("reason", "User requested cancellation")
                                     : "User requested cancellation";
        return ResponseEntity.ok(membershipService.cancelSubscription(id, reason));
    }

    @PostMapping("/subscriptions/{id}/renew")
    @Operation(summary = "Renew expired subscription")
    public ResponseEntity<SubscriptionDTO> renewSubscription(@PathVariable Long id) {
        return ResponseEntity.ok(membershipService.renewSubscription(id));
    }

    @PutMapping("/subscriptions/{id}/upgrade")
    @Operation(summary = "Upgrade subscription to higher tier")
    public ResponseEntity<SubscriptionDTO> upgradeSubscription(
            @PathVariable Long id,
            @RequestBody Map<String, Long> body) {
        return ResponseEntity.ok(membershipService.upgradeSubscription(id, body.get("newPlanId")));
    }

    @PostMapping("/subscriptions/{id}/downgrade")
    @Operation(summary = "Downgrade subscription to lower tier")
    public ResponseEntity<SubscriptionDTO> downgradeSubscription(
            @PathVariable Long id,
            @RequestBody Map<String, Long> body) {
        return ResponseEntity.ok(membershipService.downgradeSubscription(id, body.get("newPlanId")));
    }

    // ─── Observability ────────────────────────────────────────────────────────

    @GetMapping("/health")
    @Operation(summary = "System health and key metrics")
    public ResponseEntity<Map<String, Object>> getSystemHealth() {
        Map<String, Object> health = new HashMap<>();
        try {
            List<SubscriptionDTO> all = membershipService.getAllSubscriptions();
            long activeCount = all.stream()
                    .filter(s -> s.getStatus() == Subscription.SubscriptionStatus.ACTIVE)
                    .count();
            long uniqueUsers = all.stream().map(SubscriptionDTO::getUserId).distinct().count();

            Map<String, Long> tierDist = all.stream()
                    .filter(s -> s.getStatus() == Subscription.SubscriptionStatus.ACTIVE)
                    .collect(Collectors.groupingBy(SubscriptionDTO::getTier, Collectors.counting()));

            health.put("status", "UP");
            health.put("timestamp", LocalDateTime.now());
            health.put("metrics", Map.of(
                "totalUsers", uniqueUsers,
                "activeSubscriptions", activeCount,
                "availablePlans", membershipService.getActivePlans().size(),
                "membershipTiers", membershipService.getAllTiers().size(),
                "tierDistribution", tierDist
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
        List<SubscriptionDTO> all = membershipService.getAllSubscriptions();

        BigDecimal totalRevenue = all.stream()
                .filter(s -> s.getStatus() == Subscription.SubscriptionStatus.ACTIVE)
                .map(SubscriptionDTO::getPaidAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Long> tierPopularity = all.stream()
                .filter(s -> s.getStatus() == Subscription.SubscriptionStatus.ACTIVE)
                .collect(Collectors.groupingBy(SubscriptionDTO::getTier, Collectors.counting()));

        Map<String, Long> planTypeDistribution = all.stream()
                .filter(s -> s.getStatus() == Subscription.SubscriptionStatus.ACTIVE)
                .collect(Collectors.groupingBy(SubscriptionDTO::getPlanType, Collectors.counting()));

        long activeCount = tierPopularity.values().stream().mapToLong(Long::longValue).sum();

        Map<String, Object> analytics = new HashMap<>();
        analytics.put("revenue", Map.of(
            "totalRevenue", totalRevenue,
            "currency", "INR",
            "averageRevenuePerUser", all.isEmpty() ? 0 :
                    totalRevenue.divide(BigDecimal.valueOf(all.size()), 2, RoundingMode.HALF_UP)
        ));
        analytics.put("membership", Map.of(
            "tierPopularity", tierPopularity,
            "planTypeDistribution", planTypeDistribution,
            "totalActivePlans", membershipService.getActivePlans().size()
        ));
        analytics.put("summary", Map.of(
            "totalSubscriptions", all.size(),
            "activeSubscriptions", activeCount,
            "generatedAt", LocalDateTime.now()
        ));

        return ResponseEntity.ok(analytics);
    }
}
