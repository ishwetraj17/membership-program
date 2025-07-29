package com.firstclub.membership.controller;

import com.firstclub.membership.dto.MembershipPlanDTO;
import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.service.MembershipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST Controller specifically for membership plan operations
 * 
 * Dedicated controller for plan discovery and comparison.
 * Helps users explore available plans before subscribing.
 * 
 * Implemented by Shwet Raj
 */
@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Plan Discovery", description = "APIs for discovering and comparing membership plans")
public class PlanController {
    
    private final MembershipService membershipService;
    
    @GetMapping
    @Operation(
        summary = "Get all available plans", 
        description = "Retrieve all available membership plans with pricing, benefits, and savings calculations. Use this endpoint to show plan options to users before subscription."
    )
    @ApiResponse(
        responseCode = "200", 
        description = "Plans retrieved successfully",
        content = @Content(schema = @Schema(implementation = MembershipPlanDTO.class))
    )
    public ResponseEntity<List<MembershipPlanDTO>> getAllPlans() {
        List<MembershipPlanDTO> plans = membershipService.getActivePlans();
        log.info("Retrieved {} available plans for user selection", plans.size());
        return ResponseEntity.ok(plans);
    }
    
    @GetMapping("/grouped")
    @Operation(
        summary = "Get plans grouped by tier and duration", 
        description = "Get all plans organized by tier (Silver/Gold/Platinum) and duration (Monthly/Quarterly/Yearly) for easy comparison"
    )
    @ApiResponse(responseCode = "200", description = "Grouped plans retrieved successfully")
    public ResponseEntity<Map<String, Map<String, MembershipPlanDTO>>> getGroupedPlans() {
        List<MembershipPlanDTO> plans = membershipService.getActivePlans();
        
        // Group by tier, then by duration type
        Map<String, Map<String, MembershipPlanDTO>> groupedPlans = plans.stream()
            .collect(Collectors.groupingBy(
                MembershipPlanDTO::getTier,
                Collectors.toMap(
                    plan -> plan.getType().name(),
                    plan -> plan,
                    (existing, replacement) -> existing
                )
            ));
            
        log.info("Retrieved plans grouped by {} tiers", groupedPlans.size());
        return ResponseEntity.ok(groupedPlans);
    }
    
    @GetMapping("/tier/{tierName}")
    @Operation(
        summary = "Get plans by tier", 
        description = "Get all available duration options (Monthly/Quarterly/Yearly) for a specific tier"
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Plans for tier retrieved successfully"),
        @ApiResponse(responseCode = "404", description = "Tier not found")
    })
    public ResponseEntity<List<MembershipPlanDTO>> getPlansByTier(
            @Parameter(description = "Membership tier name", example = "GOLD") 
            @PathVariable String tierName) {
        List<MembershipPlanDTO> plans = membershipService.getPlansByTier(tierName);
        log.info("Retrieved {} plan duration options for tier: {}", plans.size(), tierName);
        return ResponseEntity.ok(plans);
    }
    
    @GetMapping("/duration/{type}")
    @Operation(
        summary = "Get plans by duration type", 
        description = "Get all tier options (Silver/Gold/Platinum) for a specific duration type"
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Plans for duration type retrieved successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid duration type")
    })
    public ResponseEntity<List<MembershipPlanDTO>> getPlansByDuration(
            @Parameter(description = "Plan duration type", example = "YEARLY") 
            @PathVariable MembershipPlan.PlanType type) {
        List<MembershipPlanDTO> plans = membershipService.getPlansByType(type);
        log.info("Retrieved {} tier options for duration: {}", plans.size(), type);
        return ResponseEntity.ok(plans);
    }
    
    @GetMapping("/type/{type}")
    @Operation(
        summary = "Get plans by type", 
        description = "Get all tier options (Silver/Gold/Platinum) for a specific plan type (alias for /duration)"
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Plans for type retrieved successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid plan type")
    })
    public ResponseEntity<List<MembershipPlanDTO>> getPlansByType(
            @Parameter(description = "Plan type", example = "MONTHLY") 
            @PathVariable MembershipPlan.PlanType type) {
        List<MembershipPlanDTO> plans = membershipService.getPlansByType(type);
        log.info("Retrieved {} tier options for plan type: {}", plans.size(), type);
        return ResponseEntity.ok(plans);
    }
    
    @GetMapping("/{id}")
    @Operation(
        summary = "Get detailed plan information", 
        description = "Get comprehensive details for a specific plan including pricing, benefits, and tier information"
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Plan details retrieved successfully"),
        @ApiResponse(responseCode = "404", description = "Plan not found")
    })
    public ResponseEntity<MembershipPlanDTO> getPlanDetails(
            @Parameter(description = "Plan ID", example = "1") 
            @PathVariable Long id) {
        return membershipService.getPlanById(id)
            .map(plan -> {
                log.info("Retrieved details for plan: {} ({})", plan.getName(), plan.getId());
                return ResponseEntity.ok(plan);
            })
            .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/compare")
    @Operation(
        summary = "Compare multiple plans", 
        description = "Compare features and pricing of multiple plans side by side"
    )
    @ApiResponse(responseCode = "200", description = "Plan comparison data retrieved successfully")
    public ResponseEntity<List<MembershipPlanDTO>> comparePlans(
            @Parameter(description = "Comma-separated list of plan IDs", example = "1,2,3") 
            @RequestParam String planIds) {
        
        List<Long> ids = List.of(planIds.split(","))
            .stream()
            .map(String::trim)
            .map(Long::valueOf)
            .toList();
            
        List<MembershipPlanDTO> comparisonPlans = ids.stream()
            .map(membershipService::getPlanById)
            .filter(java.util.Optional::isPresent)
            .map(java.util.Optional::get)
            .toList();
            
        log.info("Comparing {} plans for user evaluation", comparisonPlans.size());
        return ResponseEntity.ok(comparisonPlans);
    }
    
    @GetMapping("/recommendations")
    @Operation(
        summary = "Get plan recommendations", 
        description = "Get recommended plans based on popularity and value for money"
    )
    @ApiResponse(responseCode = "200", description = "Plan recommendations retrieved successfully")
    public ResponseEntity<Map<String, Object>> getPlanRecommendations() {
        List<MembershipPlanDTO> allPlans = membershipService.getActivePlans();
        
        // Find most popular tier (this would normally come from analytics)
        // For now, recommend Gold tier as a balanced option
        
        Map<String, Object> recommendations = Map.of(
            "mostPopular", allPlans.stream()
                .filter(p -> "GOLD".equals(p.getTier()) && p.getType() == MembershipPlan.PlanType.YEARLY)
                .findFirst()
                .orElse(null),
            "bestValue", allPlans.stream()
                .filter(p -> p.getType() == MembershipPlan.PlanType.YEARLY)
                .max((p1, p2) -> p1.getSavings().compareTo(p2.getSavings()))
                .orElse(null),
            "beginnerFriendly", allPlans.stream()
                .filter(p -> "SILVER".equals(p.getTier()) && p.getType() == MembershipPlan.PlanType.MONTHLY)
                .findFirst()
                .orElse(null),
            "explanation", Map.of(
                "mostPopular", "Gold yearly plan offers best balance of features and savings",
                "bestValue", "Yearly plans provide maximum savings compared to monthly billing",
                "beginnerFriendly", "Silver monthly plan allows you to try our service with minimal commitment"
            )
        );
        
        log.info("Generated plan recommendations for user guidance");
        return ResponseEntity.ok(recommendations);
    }
}
