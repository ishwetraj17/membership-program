package com.firstclub.membership.controller;

import com.firstclub.membership.dto.BenefitRuleDTO;
import com.firstclub.membership.dto.BenefitRuleRequest;
import com.firstclub.membership.service.BenefitRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin configuration of the commerce benefit engine. Business teams create, adjust and retire
 * benefit rules here — typed, threshold-gated, optionally category-scoped — without code changes.
 * Secured to ADMIN by the {@code /api/v1/admin/**} matcher.
 */
@RestController
@RequestMapping("/api/v1/admin/benefit-rules")
@RequiredArgsConstructor
@Tag(name = "Admin — Benefit Rules", description = "Configure commerce benefits (discounts, thresholds, fee waivers)")
public class BenefitRuleController {

    private final BenefitRuleService benefitRuleService;

    @GetMapping
    @Operation(summary = "List a tier's benefit rules (admin)")
    public ResponseEntity<List<BenefitRuleDTO>> list(@RequestParam Long tierId) {
        return ResponseEntity.ok(benefitRuleService.listByTier(tierId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a benefit rule (admin)")
    public ResponseEntity<BenefitRuleDTO> get(@PathVariable Long id) {
        return ResponseEntity.ok(benefitRuleService.get(id));
    }

    @PostMapping
    @Operation(summary = "Create a benefit rule (admin)")
    public ResponseEntity<BenefitRuleDTO> create(@Valid @RequestBody BenefitRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(benefitRuleService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a benefit rule (admin)")
    public ResponseEntity<BenefitRuleDTO> update(@PathVariable Long id,
                                                 @Valid @RequestBody BenefitRuleRequest request) {
        return ResponseEntity.ok(benefitRuleService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a benefit rule (admin)")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        benefitRuleService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
