package com.firstclub.risk.controller;

import com.firstclub.risk.dto.RiskRuleCreateRequestDTO;
import com.firstclub.risk.dto.RiskRuleResponseDTO;
import com.firstclub.risk.service.RiskRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/admin/risk/rules")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Risk Rules Admin", description = "Create and manage risk evaluation rules")
public class RiskRuleAdminController {

    private final RiskRuleService riskRuleService;

    @PostMapping
    @Operation(summary = "Create a risk rule")
    public ResponseEntity<RiskRuleResponseDTO> createRule(
            @Valid @RequestBody RiskRuleCreateRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(riskRuleService.createRule(request));
    }

    @GetMapping
    @Operation(summary = "List all risk rules (paginated)")
    public ResponseEntity<Page<RiskRuleResponseDTO>> listRules(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(riskRuleService.listRules(pageable));
    }

    @PutMapping("/{ruleId}")
    @Operation(summary = "Update an existing risk rule")
    public ResponseEntity<RiskRuleResponseDTO> updateRule(
            @PathVariable Long ruleId,
            @Valid @RequestBody RiskRuleCreateRequestDTO request) {
        return ResponseEntity.ok(riskRuleService.updateRule(ruleId, request));
    }
}
