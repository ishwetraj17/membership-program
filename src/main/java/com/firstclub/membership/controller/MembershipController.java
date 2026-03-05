package com.firstclub.membership.controller;

import com.firstclub.membership.dto.*;
import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.service.MembershipService;
import com.firstclub.membership.service.PlanService;
import com.firstclub.membership.service.TierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for administrative membership operations
 * 
 * Administrative controller for membership system management.
 * Exposes plan/tier catalogue and system analytics.
 * Subscription CRUD has been consolidated into SubscriptionController.
 * 
 * Implemented by Shwet Raj
 */
@RestController
@RequestMapping("/api/v1/membership")
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "Membership Management", description = "Plan/tier catalogue and system analytics")
public class MembershipController {
    
    private final MembershipService membershipService;
    private final TierService tierService;
    private final PlanService planService;
    
    // Plan endpoints
    @GetMapping("/plans")
    @Operation(summary = "Get all membership plans", description = "Retrieves all available plans with pricing in INR")
    @ApiResponse(responseCode = "200", description = "Plans retrieved successfully")
    public ResponseEntity<List<MembershipPlanDTO>> getAllPlans() {
        List<MembershipPlanDTO> plans = planService.getActivePlans();
        log.debug("Retrieved {} membership plans", plans.size());
        return ResponseEntity.ok(plans);
    }
    
    @GetMapping("/plans/tier/{tierName}")
    @Operation(summary = "Get plans by tier name", description = "Get all plans for a specific tier by name")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Plans retrieved successfully"),
        @ApiResponse(responseCode = "404", description = "Tier not found")
    })
    public ResponseEntity<List<MembershipPlanDTO>> getPlansByTier(
            @Parameter(description = "Tier name", example = "GOLD") @PathVariable String tierName) {
        List<MembershipPlanDTO> plans = planService.getPlansByTier(tierName);
        log.debug("Retrieved {} plans for tier: {}", plans.size(), tierName);
        return ResponseEntity.ok(plans);
    }
    
    @GetMapping("/plans/tier-id/{tierId}")
    @Operation(summary = "Get plans by tier ID", description = "Get all plans for a specific tier by ID")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Plans retrieved successfully"),
        @ApiResponse(responseCode = "404", description = "Tier not found")
    })
    public ResponseEntity<List<MembershipPlanDTO>> getPlansByTierId(
            @Parameter(description = "Tier ID", example = "1") @Positive @PathVariable Long tierId) {
        List<MembershipPlanDTO> plans = planService.getPlansByTierId(tierId);
        log.debug("Retrieved {} plans for tier ID: {}", plans.size(), tierId);
        return ResponseEntity.ok(plans);
    }
    
    @GetMapping("/plans/type/{type}")
    @Operation(summary = "Get plans by type", description = "Get plans by duration type")
    public ResponseEntity<List<MembershipPlanDTO>> getPlansByType(
            @Parameter(description = "Plan type", example = "YEARLY") @PathVariable MembershipPlan.PlanType type) {
        List<MembershipPlanDTO> plans = planService.getPlansByType(type);
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
            @Parameter(description = "Plan ID", example = "1") @Positive @PathVariable Long id) {
        return planService.getPlanById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
    
    // Tier endpoints
    @GetMapping("/tiers")
    @Operation(summary = "Get all membership tiers", description = "Get all available tiers with benefits")
    @ApiResponse(responseCode = "200", description = "Tiers retrieved successfully")
    public ResponseEntity<List<MembershipTierDTO>> getAllTiers() {
        List<MembershipTierDTO> tiers = tierService.getAllTiers();
        log.debug("Retrieved {} membership tiers", tiers.size());
        return ResponseEntity.ok(tiers);
    }
    
    @GetMapping("/tiers/{name}")
    @Operation(summary = "Get tier by name", description = "Get specific tier details")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Tier found"),
        @ApiResponse(responseCode = "404", description = "Tier not found")
    })
    public ResponseEntity<MembershipTierDTO> getTierByName(
            @Parameter(description = "Tier name", example = "PLATINUM") @PathVariable String name) {
        return tierService.getTierByName(name)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/tiers/id/{id}")
    @Operation(summary = "Get tier by ID", description = "Get specific tier details by ID")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Tier found"),
        @ApiResponse(responseCode = "404", description = "Tier not found")
    })
    public ResponseEntity<MembershipTierDTO> getTierById(
            @Parameter(description = "Tier ID", example = "1") @Positive @PathVariable Long id) {
        return tierService.getTierById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
    
    // Health and Analytics endpoints
    @GetMapping("/health")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "System health check and metrics", 
        description = """
            Comprehensive system health check including:
            • System status and uptime
            • Database connectivity
            • Key metrics (users, subscriptions, plans)
            • Tier distribution analysis
            • Environment and version information
            
            Use this endpoint to monitor system health and get quick overview metrics.
            """
    )
    @ApiResponse(responseCode = "200", description = "System health retrieved successfully")
    @ApiResponse(responseCode = "503", description = "System is experiencing issues")
    @Tag(name = "Analytics & Health")
    public ResponseEntity<SystemHealthDTO> getSystemHealth() {
        try {
            SystemHealthDTO health = membershipService.getSystemHealth();
            return ResponseEntity.ok(health);
        } catch (Exception e) {
            log.error("Health check failed", e);
            SystemHealthDTO down = SystemHealthDTO.builder()
                .status("DOWN")
                .error(e.getMessage())
                .build();
            return ResponseEntity.status(503).body(down);
        }
    }
    
    @GetMapping("/analytics")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Business analytics and insights", 
        description = """
            Get comprehensive business analytics including:
            • Revenue metrics by tier and plan type
            • Tier popularity analysis
            • Plan type distribution
            • Average revenue per user
            • Active subscription metrics
            
            This endpoint provides key business intelligence for management decisions.
            """
    )
    @ApiResponse(responseCode = "200", description = "Analytics retrieved successfully")
    @Tag(name = "Analytics & Health")
    public ResponseEntity<AnalyticsDTO> getAnalytics() {
        AnalyticsDTO analytics = membershipService.getAnalytics();
        return ResponseEntity.ok(analytics);
    }
}