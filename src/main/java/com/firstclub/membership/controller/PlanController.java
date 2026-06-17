package com.firstclub.membership.controller;

import com.firstclub.membership.dto.CreatePlanRequest;
import com.firstclub.membership.dto.MembershipPlanDTO;
import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.service.PlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
@Tag(name = "Plan Discovery", description = "Browse and compare membership plans before subscribing")
public class PlanController {

    private final PlanService planService;

    @GetMapping
    @Operation(summary = "All active plans", description = "Returns all 9 plans (3 tiers × 3 durations) with pricing and savings calculations.")
    @ApiResponse(responseCode = "200", description = "OK")
    public ResponseEntity<List<MembershipPlanDTO>> getAllPlans() {
        return ResponseEntity.ok(planService.getActivePlans());
    }

    @GetMapping("/grouped")
    @Operation(summary = "Plans grouped by tier and duration", description = "Returns a nested map of tier → duration → plan, useful for building a plan comparison table.")
    @ApiResponse(responseCode = "200", description = "OK")
    public ResponseEntity<Map<String, Map<String, MembershipPlanDTO>>> getGroupedPlans() {
        List<MembershipPlanDTO> plans = planService.getActivePlans();
        Map<String, Map<String, MembershipPlanDTO>> grouped = plans.stream()
            .collect(Collectors.groupingBy(
                MembershipPlanDTO::getTier,
                Collectors.toMap(p -> p.getType().name(), p -> p, (a, b) -> a)));
        return ResponseEntity.ok(grouped);
    }

    @GetMapping("/tier/{tierName}")
    @Operation(summary = "Plans by tier", description = "Returns all duration options for the given tier name (SILVER, GOLD, PLATINUM).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "404", description = "Tier not found")
    })
    public ResponseEntity<List<MembershipPlanDTO>> getPlansByTier(
            @Parameter(description = "Tier name", example = "GOLD") @PathVariable String tierName) {
        return ResponseEntity.ok(planService.getPlansByTier(tierName));
    }

    @GetMapping("/type/{type}")
    @Operation(summary = "Plans by duration type", description = "Returns all tier options for the given duration type (MONTHLY, QUARTERLY, YEARLY).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Invalid type")
    })
    public ResponseEntity<List<MembershipPlanDTO>> getPlansByType(
            @Parameter(description = "Plan type", example = "YEARLY") @PathVariable MembershipPlan.PlanType type) {
        return ResponseEntity.ok(planService.getPlansByType(type));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Plan details by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Plan found"),
        @ApiResponse(responseCode = "404", description = "Plan not found")
    })
    public ResponseEntity<MembershipPlanDTO> getPlanById(
            @Parameter(description = "Plan ID", example = "1") @PathVariable Long id) {
        return ResponseEntity.ok(planService.getPlanById(id)
                .orElseThrow(() -> MembershipException.planNotFound(id)));
    }

    @GetMapping("/compare")
    @Operation(summary = "Compare plans side by side", description = "Pass comma-separated plan IDs to retrieve them together for comparison.")
    @ApiResponse(responseCode = "200", description = "OK")
    public ResponseEntity<List<MembershipPlanDTO>> comparePlans(
            @Parameter(description = "Comma-separated plan IDs", example = "1,4,7") @RequestParam String planIds) {
        List<MembershipPlanDTO> plans = java.util.Arrays.stream(planIds.split(","))
            .map(s -> {
                try { return Long.valueOf(s.trim()); }
                catch (NumberFormatException e) {
                    throw new MembershipException(
                        "Invalid plan ID '" + s.trim() + "' — must be a positive integer",
                        "INVALID_PARAMETER_VALUE");
                }
            })
            .map(id -> planService.getPlanById(id)
                    .orElseThrow(() -> MembershipException.planNotFound(id)))
            .toList();
        return ResponseEntity.ok(plans);
    }

    @PostMapping
    @Operation(summary = "Create a plan (admin)")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Plan created"),
        @ApiResponse(responseCode = "404", description = "Tier not found")
    })
    public ResponseEntity<MembershipPlanDTO> createPlan(@Valid @RequestBody CreatePlanRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(planService.createPlan(request));
    }

    @PutMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a plan (admin)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Plan deactivated"),
        @ApiResponse(responseCode = "404", description = "Plan not found")
    })
    public ResponseEntity<MembershipPlanDTO> deactivatePlan(@PathVariable Long id) {
        return ResponseEntity.ok(planService.deactivatePlan(id));
    }

    @GetMapping("/recommendations")
    @Operation(summary = "Recommended plans", description = "Returns opinionated plan picks: most popular (Gold Yearly), best value (highest savings), and beginner-friendly (Silver Monthly).")
    @ApiResponse(responseCode = "200", description = "OK")
    public ResponseEntity<Map<String, Object>> getRecommendations() {
        List<MembershipPlanDTO> all = planService.getActivePlans();
        Map<String, Object> recommendations = new java.util.LinkedHashMap<>();
        all.stream().filter(p -> "GOLD".equals(p.getTier()) && p.getType() == MembershipPlan.PlanType.YEARLY)
            .findFirst().ifPresent(p -> recommendations.put("mostPopular", p));
        all.stream().filter(p -> p.getType() == MembershipPlan.PlanType.YEARLY)
            .max(java.util.Comparator.comparing(MembershipPlanDTO::getSavings))
            .ifPresent(p -> recommendations.put("bestValue", p));
        all.stream().filter(p -> "SILVER".equals(p.getTier()) && p.getType() == MembershipPlan.PlanType.MONTHLY)
            .findFirst().ifPresent(p -> recommendations.put("beginnerFriendly", p));
        return ResponseEntity.ok(recommendations);
    }
}
