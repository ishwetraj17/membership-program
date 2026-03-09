package com.firstclub.risk.controller;

import com.firstclub.risk.dto.ManualReviewCaseResponseDTO;
import com.firstclub.risk.dto.ManualReviewResolveRequestDTO;
import com.firstclub.risk.entity.ReviewCaseStatus;
import com.firstclub.risk.service.ManualReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/admin/risk/review-cases")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Manual Review Cases Admin", description = "Manage the manual review queue for high-risk payments")
public class ManualReviewAdminController {

    private final ManualReviewService reviewService;

    @GetMapping
    @Operation(summary = "List manual review cases, optionally filtered by status")
    public ResponseEntity<Page<ManualReviewCaseResponseDTO>> listCases(
            @RequestParam(required = false) ReviewCaseStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(reviewService.listCases(status, pageable));
    }

    @PostMapping("/{caseId}/assign")
    @Operation(summary = "Assign a review case to a user")
    public ResponseEntity<ManualReviewCaseResponseDTO> assignCase(
            @PathVariable Long caseId,
            @RequestParam Long userId) {
        return ResponseEntity.ok(reviewService.assignCase(caseId, userId));
    }

    @PostMapping("/{caseId}/resolve")
    @Operation(summary = "Resolve a manual review case (APPROVED, REJECTED, ESCALATED, CLOSED)")
    public ResponseEntity<ManualReviewCaseResponseDTO> resolveCase(
            @PathVariable Long caseId,
            @Valid @RequestBody ManualReviewResolveRequestDTO request) {
        return ResponseEntity.ok(reviewService.resolveCase(caseId, request));
    }
}
