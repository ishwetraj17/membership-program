package com.firstclub.membership.controller;

import com.firstclub.membership.dto.*;
import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.entity.MembershipTier;
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

/**
 * REST Controller for membership and subscription operations
 * 
 * Main controller handling plans, tiers, subscriptions, and analytics.
 * Includes custom health check and analytics endpoints.
 * 
 * Implemented by Shwet Raj
 */
@RestController
@RequestMapping("/api/v1/membership")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Membership Management", description = "APIs for managing memberships and subscriptions")
public class MembershipController {
    
    private final MembershipService membershipService;
    
    // Plan endpoints
    @GetMapping("/plans")
    @Operation(summary = "Get all membership plans", description = "Retrieves all available plans with pricing in INR")
    @ApiResponse(responseCode = "200", description = "Plans retrieved successfully")
    public ResponseEntity<List<MembershipPlanDTO>> getAllPlans() {
        List<MembershipPlanDTO> plans = membershipService.getActivePlans();
        log.debug("Retrieved {} membership plans", plans.size());
        return ResponseEntity.ok(plans);
    }
    
    @GetMapping("/plans/tier/{tierName}")
    @Operation(summary = "Get plans by tier", description = "Get all plans for a specific tier")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Plans retrieved successfully"),
        @ApiResponse(responseCode = "404", description = "Tier not found")
    })
    public ResponseEntity<List<MembershipPlanDTO>> getPlansByTier(
            @Parameter(description = "Tier name", example = "GOLD") @PathVariable String tierName) {
        List<MembershipPlanDTO> plans = membershipService.getPlansByTier(tierName);
        log.debug("Retrieved {} plans for tier: {}", plans.size(), tierName);
        return ResponseEntity.ok(plans);
    }
    
    @GetMapping("/plans/type/{type}")
    @Operation(summary = "Get plans by type", description = "Get plans by duration type")
    public ResponseEntity<List<MembershipPlanDTO>> getPlansByType(
            @Parameter(description = "Plan type", example = "YEARLY") @PathVariable MembershipPlan.PlanType type) {
        List<MembershipPlanDTO> plans = membershipService.getPlansByType(type);
        log.debug("Retrieved {} plans for type: {}", plans.size(), type);
        return ResponseEntity.ok(plans);
    }
    
    @GetMapping("/plans/{id}")
    @Operation(summary = "Get plan by ID", description = "Get detailed plan information")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Plan found"),
        @ApiResponse(responseCode = "404", description = "Plan not found")
    })
    public ResponseEntity<MembershipPlanDTO> getPlanById(
            @Parameter(description = "Plan ID", example = "1") @PathVariable Long id) {
        return membershipService.getPlanById(id)
            .map(plan -> ResponseEntity.ok(plan))
            .orElse(ResponseEntity.notFound().build());
    }
    
    // Tier endpoints
    @GetMapping("/tiers")
    @Operation(summary = "Get all membership tiers", description = "Get all available tiers with benefits")
    @ApiResponse(responseCode = "200", description = "Tiers retrieved successfully")
    public ResponseEntity<List<MembershipTier>> getAllTiers() {
        List<MembershipTier> tiers = membershipService.getAllTiers();
        log.debug("Retrieved {} membership tiers", tiers.size());
        return ResponseEntity.ok(tiers);
    }
    
    @GetMapping("/tiers/{name}")
    @Operation(summary = "Get tier by name", description = "Get specific tier details")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Tier found"),
        @ApiResponse(responseCode = "404", description = "Tier not found")
    })
    public ResponseEntity<MembershipTier> getTierByName(
            @Parameter(description = "Tier name", example = "PLATINUM") @PathVariable String name) {
        return membershipService.getTierByName(name)
            .map(tier -> ResponseEntity.ok(tier))
            .orElse(ResponseEntity.notFound().build());
    }
    
    // Subscription endpoints
    @PostMapping("/subscriptions")
    @Operation(summary = "Create subscription", description = "Create new membership subscription")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Subscription created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request"),
        @ApiResponse(responseCode = "409", description = "User already has active subscription")
    })
    public ResponseEntity<SubscriptionDTO> createSubscription(
            @Valid @RequestBody SubscriptionRequestDTO request) {
        log.info("Creating subscription for user: {} with plan: {}", request.getUserId(), request.getPlanId());
        SubscriptionDTO subscription = membershipService.createSubscription(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(subscription);
    }
    
    @GetMapping("/subscriptions/user/{userId}")
    @Operation(summary = "Get user subscriptions", description = "Get all subscriptions for a user")
    @ApiResponse(responseCode = "200", description = "Subscriptions retrieved successfully")
    public ResponseEntity<List<SubscriptionDTO>> getUserSubscriptions(
            @Parameter(description = "User ID", example = "1") @PathVariable Long userId) {
        List<SubscriptionDTO> subscriptions = membershipService.getUserSubscriptions(userId);
        log.debug("Retrieved {} subscriptions for user: {}", subscriptions.size(), userId);
        return ResponseEntity.ok(subscriptions);
    }
    
    @GetMapping("/subscriptions/user/{userId}/active")
    @Operation(summary = "Get active subscription", description = "Get user's current active subscription")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Active subscription found"),
        @ApiResponse(responseCode = "404", description = "No active subscription")
    })
    public ResponseEntity<SubscriptionDTO> getActiveSubscription(
            @Parameter(description = "User ID", example = "1") @PathVariable Long userId) {
        return membershipService.getActiveSubscription(userId)
            .map(subscription -> ResponseEntity.ok(subscription))
            .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/subscriptions")
    @Operation(summary = "Get all subscriptions", description = "Get all subscriptions (admin only)")
    @ApiResponse(responseCode = "200", description = "All subscriptions retrieved")
    public ResponseEntity<List<SubscriptionDTO>> getAllSubscriptions() {
        List<SubscriptionDTO> subscriptions = membershipService.getAllSubscriptions();
        log.debug("Retrieved {} total subscriptions", subscriptions.size());
        return ResponseEntity.ok(subscriptions);
    }
    
    @PutMapping("/subscriptions/{id}")
    @Operation(summary = "Update subscription", description = "Update subscription settings")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Subscription updated"),
        @ApiResponse(responseCode = "404", description = "Subscription not found")
    })
    public ResponseEntity<SubscriptionDTO> updateSubscription(
            @Parameter(description = "Subscription ID", example = "1") @PathVariable Long id,
            @RequestBody SubscriptionUpdateDTO updateDTO) {
        log.info("Updating subscription: {}", id);
        SubscriptionDTO subscription = membershipService.updateSubscription(id, updateDTO);
        return ResponseEntity.ok(subscription);
    }
    
    @PostMapping("/subscriptions/{id}/cancel")
    @Operation(summary = "Cancel subscription", description = "Cancel active subscription")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Subscription cancelled"),
        @ApiResponse(responseCode = "404", description = "Subscription not found"),
        @ApiResponse(responseCode = "400", description = "Cannot cancel inactive subscription")
    })
    public ResponseEntity<SubscriptionDTO> cancelSubscription(
            @Parameter(description = "Subscription ID", example = "1") @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> request) {
        String reason = request != null ? request.get("reason") : "User requested cancellation";
        log.info("Cancelling subscription: {} with reason: {}", id, reason);
        SubscriptionDTO subscription = membershipService.cancelSubscription(id, reason);
        return ResponseEntity.ok(subscription);
    }
    
    @PostMapping("/subscriptions/{id}/renew")
    @Operation(summary = "Renew subscription", description = "Renew expired subscription")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Subscription renewed"),
        @ApiResponse(responseCode = "404", description = "Subscription not found"),
        @ApiResponse(responseCode = "400", description = "Only expired subscriptions can be renewed")
    })
    public ResponseEntity<SubscriptionDTO> renewSubscription(
            @Parameter(description = "Subscription ID", example = "1") @PathVariable Long id) {
        log.info("Renewing subscription: {}", id);
        SubscriptionDTO subscription = membershipService.renewSubscription(id);
        return ResponseEntity.ok(subscription);
    }
    
    @PostMapping("/subscriptions/{id}/upgrade")
    @Operation(summary = "Upgrade subscription", description = "Upgrade to higher tier")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Subscription upgraded"),
        @ApiResponse(responseCode = "404", description = "Subscription or plan not found"),
        @ApiResponse(responseCode = "400", description = "Invalid upgrade")
    })
    public ResponseEntity<SubscriptionDTO> upgradeSubscription(
            @Parameter(description = "Subscription ID", example = "1") @PathVariable Long id,
            @RequestBody Map<String, Long> request) {
        Long newPlanId = request.get("newPlanId");
        log.info("Upgrading subscription: {} to plan: {}", id, newPlanId);
        SubscriptionDTO subscription = membershipService.upgradeSubscription(id, newPlanId);
        return ResponseEntity.ok(subscription);
    }
    
    @PostMapping("/subscriptions/{id}/downgrade")
    @Operation(summary = "Downgrade subscription", description = "Downgrade to lower tier")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Subscription downgraded"),
        @ApiResponse(responseCode = "404", description = "Subscription or plan not found"),
        @ApiResponse(responseCode = "400", description = "Invalid downgrade")
    })
    public ResponseEntity<SubscriptionDTO> downgradeSubscription(
            @Parameter(description = "Subscription ID", example = "1") @PathVariable Long id,
            @RequestBody Map<String, Long> request) {
        Long newPlanId = request.get("newPlanId");
        log.info("Downgrading subscription: {} to plan: {}", id, newPlanId);
        SubscriptionDTO subscription = membershipService.downgradeSubscription(id, newPlanId);
        return ResponseEntity.ok(subscription);
    }
    
    // Health and Analytics endpoints
    @GetMapping("/health")
    @Operation(summary = "System health check", description = "Get system health and metrics")
    @ApiResponse(responseCode = "200", description = "System health retrieved")
    public ResponseEntity<Map<String, Object>> getSystemHealth() {
        Map<String, Object> health = new HashMap<>();
        
        try {
            // Basic system info
            health.put("status", "UP");
            health.put("timestamp", LocalDateTime.now());
            health.put("developer", "Shwet Raj");
            health.put("version", "1.0.0");
            
            // Get some basic metrics
            long totalUsers = membershipService.getAllSubscriptions().stream()
                .map(SubscriptionDTO::getUserId)
                .distinct()
                .count();
            
            long activeSubscriptions = membershipService.getAllSubscriptions().stream()
                .filter(s -> s.getStatus() == Subscription.SubscriptionStatus.ACTIVE)
                .count();
            
            Map<String, Long> tierDistribution = membershipService.getAllSubscriptions().stream()
                .filter(s -> s.getStatus() == Subscription.SubscriptionStatus.ACTIVE)
                .collect(Collectors.groupingBy(
                    SubscriptionDTO::getTier,
                    Collectors.counting()
                ));
            
            health.put("metrics", Map.of(
                "totalUsers", totalUsers,
                "activeSubscriptions", activeSubscriptions,
                "availablePlans", membershipService.getActivePlans().size(),
                "membershipTiers", 3,
                "tierDistribution", tierDistribution
            ));
            
            // System details
            health.put("system", Map.of(
                "java.version", System.getProperty("java.version"),
                "database", "H2 In-Memory",
                "environment", "Development"
            ));
            
            return ResponseEntity.ok(health);
            
        } catch (Exception e) {
            health.put("status", "DOWN");
            health.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(health);
        }
    }
    
    @GetMapping("/analytics")
    @Operation(summary = "Membership analytics", description = "Get business analytics and insights")
    @ApiResponse(responseCode = "200", description = "Analytics retrieved successfully")
    public ResponseEntity<Map<String, Object>> getAnalytics() {
        Map<String, Object> analytics = new HashMap<>();
        
        // System.out.println("DEBUG: Generating analytics report");
        // used during testing - can be removed later
        
        List<SubscriptionDTO> allSubscriptions = membershipService.getAllSubscriptions();
        List<MembershipPlanDTO> allPlans = membershipService.getActivePlans();
        
        // Revenue calculations
        BigDecimal totalRevenue = allSubscriptions.stream()
            .filter(s -> s.getStatus() == Subscription.SubscriptionStatus.ACTIVE)
            .map(SubscriptionDTO::getPaidAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
            
        // Tier popularity analysis
        Map<String, Long> tierPopularity = allSubscriptions.stream()
            .filter(s -> s.getStatus() == Subscription.SubscriptionStatus.ACTIVE)
            .collect(Collectors.groupingBy(
                SubscriptionDTO::getTier,
                Collectors.counting()
            ));
            
        // Plan type distribution
        Map<String, Long> planTypeDistribution = allSubscriptions.stream()
            .filter(s -> s.getStatus() == Subscription.SubscriptionStatus.ACTIVE)
            .collect(Collectors.groupingBy(
                SubscriptionDTO::getPlanType,
                Collectors.counting()
            ));
            
        // TODO: Add more sophisticated analytics like churn rate, LTV etc
        
        analytics.put("revenue", Map.of(
            "totalRevenue", totalRevenue,
            "currency", "INR",
            "averageRevenuePerUser", allSubscriptions.isEmpty() ? 0 : 
                totalRevenue.divide(new BigDecimal(allSubscriptions.size()), 2, RoundingMode.HALF_UP)
        ));
        
        analytics.put("membership", Map.of(
            "tierPopularity", tierPopularity,
            "planTypeDistribution", planTypeDistribution,
            "totalActivePlans", allPlans.size()
        ));
        
        analytics.put("summary", Map.of(
            "totalSubscriptions", allSubscriptions.size(),
            "activeSubscriptions", tierPopularity.values().stream().mapToLong(Long::longValue).sum(),
            "generatedAt", LocalDateTime.now(),
            "developer", "Shwet Raj"
        ));
        
        return ResponseEntity.ok(analytics);
    }
}