package com.firstclub.dunning.controller;

import com.firstclub.dunning.dto.DunningPolicyCreateRequestDTO;
import com.firstclub.dunning.dto.DunningPolicyResponseDTO;
import com.firstclub.dunning.service.DunningPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Merchant-scoped dunning policy endpoints.
 *
 * <p>All paths are prefixed with
 * {@code /api/v2/merchants/{merchantId}/dunning-policies}.
 */
@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/dunning-policies")
@RequiredArgsConstructor
@Tag(name = "Dunning Policies (v2)",
     description = "Create and retrieve merchant-specific dunning retry policies")
public class DunningPolicyController {

    private final DunningPolicyService dunningPolicyService;

    @PostMapping
    @Operation(
        summary = "Create a dunning policy",
        description = "Defines a named retry schedule for a merchant. "
                + "retryOffsetsJson must be a JSON array of positive integers (minutes). "
                + "statusAfterExhaustion must be SUSPENDED or CANCELLED.")
    public ResponseEntity<DunningPolicyResponseDTO> createPolicy(
            @Parameter(description = "Merchant identifier", required = true)
            @PathVariable Long merchantId,
            @Valid @RequestBody DunningPolicyCreateRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(dunningPolicyService.createPolicy(merchantId, request));
    }

    @GetMapping
    @Operation(summary = "List all dunning policies for a merchant")
    public ResponseEntity<List<DunningPolicyResponseDTO>> listPolicies(
            @PathVariable Long merchantId) {
        return ResponseEntity.ok(dunningPolicyService.listPolicies(merchantId));
    }

    @GetMapping("/{policyCode}")
    @Operation(summary = "Get a dunning policy by code")
    public ResponseEntity<DunningPolicyResponseDTO> getPolicyByCode(
            @PathVariable Long merchantId,
            @Parameter(description = "Policy code (e.g. DEFAULT)", required = true)
            @PathVariable String policyCode) {
        return ResponseEntity.ok(dunningPolicyService.getPolicyByCode(merchantId, policyCode));
    }

    @GetMapping("/id/{policyId}")
    @Operation(
        summary = "Get a dunning policy by numeric ID",
        description = "Fetches a dunning policy by its database ID rather than its policy code. "
                + "Useful when the caller has a policy ID from a dunning attempt record.")
    public ResponseEntity<DunningPolicyResponseDTO> getPolicyById(
            @PathVariable Long merchantId,
            @Parameter(description = "Numeric policy ID", required = true)
            @PathVariable Long policyId) {
        return ResponseEntity.ok(dunningPolicyService.getPolicyById(merchantId, policyId));
    }
}
