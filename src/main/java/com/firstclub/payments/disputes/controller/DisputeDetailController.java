package com.firstclub.payments.disputes.controller;

import com.firstclub.payments.disputes.dto.DisputeEvidenceCreateRequestDTO;
import com.firstclub.payments.disputes.dto.DisputeEvidenceResponseDTO;
import com.firstclub.payments.disputes.dto.DisputeResponseDTO;
import com.firstclub.payments.disputes.dto.DisputeResolveRequestDTO;
import com.firstclub.payments.disputes.entity.DisputeStatus;
import com.firstclub.payments.disputes.service.DisputeEvidenceService;
import com.firstclub.payments.disputes.service.DisputeService;
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
 * Merchant-scoped dispute management endpoints.
 *
 * <p>Base path: {@code /api/v2/merchants/{merchantId}/disputes}.
 * Covers dispute review, resolution, and evidence upload/listing.
 */
@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/disputes")
@RequiredArgsConstructor
@Tag(name = "Disputes (Merchant-Scoped)",
     description = "Dispute lifecycle: review, resolve, evidence management")
public class DisputeDetailController {

    private final DisputeService         disputeService;
    private final DisputeEvidenceService evidenceService;

    /**
     * List all disputes for a merchant, optionally filtered by {@code status}.
     */
    @GetMapping
    @Operation(
        summary = "List disputes for a merchant",
        description = "Returns all disputes belonging to the merchant. "
                + "Optionally filter by status (OPEN, UNDER_REVIEW, WON, LOST, CLOSED).")
    public ResponseEntity<List<DisputeResponseDTO>> listDisputes(
            @PathVariable Long merchantId,
            @Parameter(description = "Optional status filter")
            @RequestParam(required = false) DisputeStatus status) {
        return ResponseEntity.ok(disputeService.listDisputes(merchantId, status));
    }

    /**
     * Get a single dispute by ID.
     */
    @GetMapping("/{disputeId}")
    @Operation(summary = "Get dispute by ID")
    public ResponseEntity<DisputeResponseDTO> getDispute(
            @PathVariable Long merchantId,
            @PathVariable Long disputeId) {
        return ResponseEntity.ok(disputeService.getDisputeById(merchantId, disputeId));
    }

    /**
     * Move a dispute from OPEN to UNDER_REVIEW.
     */
    @PostMapping("/{disputeId}/review")
    @Operation(
        summary = "Move dispute to UNDER_REVIEW",
        description = "Transitions dispute status from OPEN to UNDER_REVIEW. Only valid when current status is OPEN.")
    public ResponseEntity<DisputeResponseDTO> moveToUnderReview(
            @PathVariable Long merchantId,
            @PathVariable Long disputeId) {
        return ResponseEntity.ok(disputeService.moveToUnderReview(merchantId, disputeId));
    }

    /**
     * Resolve a dispute as WON or LOST.
     */
    @PostMapping("/{disputeId}/resolve")
    @Operation(
        summary = "Resolve a dispute",
        description = "Resolves an OPEN or UNDER_REVIEW dispute. "
                + "WON: releases reserve, restores payment status. "
                + "LOST: posts CHARGEBACK_EXPENSE, permanently reduces capturedAmount.")
    public ResponseEntity<DisputeResponseDTO> resolveDispute(
            @PathVariable Long merchantId,
            @PathVariable Long disputeId,
            @Valid @RequestBody DisputeResolveRequestDTO request) {
        return ResponseEntity.ok(disputeService.resolveDispute(merchantId, disputeId, request));
    }

    /**
     * Upload evidence for a dispute. Only allowed before the due_by deadline.
     */
    @PostMapping("/{disputeId}/evidence")
    @Operation(
        summary = "Upload dispute evidence",
        description = "Submits a piece of evidence. Blocked after the due_by deadline. Evidence is immutable.")
    public ResponseEntity<DisputeEvidenceResponseDTO> addEvidence(
            @PathVariable Long merchantId,
            @PathVariable Long disputeId,
            @Valid @RequestBody DisputeEvidenceCreateRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(evidenceService.addEvidence(merchantId, disputeId, request));
    }

    /**
     * List all evidence for a dispute (oldest first).
     */
    @GetMapping("/{disputeId}/evidence")
    @Operation(summary = "List evidence for a dispute")
    public ResponseEntity<List<DisputeEvidenceResponseDTO>> listEvidence(
            @PathVariable Long merchantId,
            @PathVariable Long disputeId) {
        return ResponseEntity.ok(evidenceService.listEvidence(merchantId, disputeId));
    }
}
