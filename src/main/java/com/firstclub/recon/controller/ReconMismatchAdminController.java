package com.firstclub.recon.controller;

import com.firstclub.recon.dto.ReconMismatchDTO;
import com.firstclub.recon.dto.ReconMismatchResolveRequestDTO;
import com.firstclub.recon.entity.ReconMismatchStatus;
import com.firstclub.recon.service.AdvancedReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v2/admin/recon/mismatches")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Recon Mismatches", description = "Mismatch lifecycle management")
public class ReconMismatchAdminController {

    private final AdvancedReconciliationService service;

    @GetMapping
    @Operation(summary = "List mismatches, optionally filtered by status")
    public ResponseEntity<Page<ReconMismatchDTO>> listMismatches(
            @RequestParam(required = false) ReconMismatchStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(service.listMismatches(status, pageable));
    }

    @PostMapping("/{mismatchId}/acknowledge")
    @Operation(summary = "Acknowledge a mismatch and assign an owner")
    public ResponseEntity<ReconMismatchDTO> acknowledge(
            @PathVariable Long mismatchId,
            @RequestBody Map<String, Long> body) {
        Long ownerUserId = body.get("ownerUserId");
        return ResponseEntity.ok(service.acknowledgeMismatch(mismatchId, ownerUserId));
    }

    @PostMapping("/{mismatchId}/resolve")
    @Operation(summary = "Mark a mismatch as resolved with a resolution note")
    public ResponseEntity<ReconMismatchDTO> resolve(
            @PathVariable Long mismatchId,
            @RequestBody ReconMismatchResolveRequestDTO req) {
        return ResponseEntity.ok(service.resolveMismatch(mismatchId, req.getResolutionNote(), req.getOwnerUserId()));
    }

    @PostMapping("/{mismatchId}/ignore")
    @Operation(summary = "Ignore a mismatch with a reason")
    public ResponseEntity<ReconMismatchDTO> ignore(
            @PathVariable Long mismatchId,
            @RequestBody Map<String, String> body) {
        String reason = body.getOrDefault("reason", "");
        return ResponseEntity.ok(service.ignoreMismatch(mismatchId, reason));
    }
}
