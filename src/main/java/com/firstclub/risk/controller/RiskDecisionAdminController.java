package com.firstclub.risk.controller;

import com.firstclub.risk.dto.RiskDecisionResponseDTO;
import com.firstclub.risk.service.RiskDecisionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/admin/risk/decisions")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Risk Decisions Admin", description = "Inspect risk decisions recorded for payment intents")
public class RiskDecisionAdminController {

    private final RiskDecisionService decisionService;

    @GetMapping
    @Operation(summary = "List risk decisions, optionally filtered by merchant")
    public ResponseEntity<Page<RiskDecisionResponseDTO>> listDecisions(
            @RequestParam(required = false) Long merchantId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(decisionService.listDecisions(merchantId, pageable));
    }
}
