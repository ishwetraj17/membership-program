package com.firstclub.risk.controller;

import com.firstclub.risk.dto.ManualReviewCaseResponseDTO;
import com.firstclub.risk.dto.ManualReviewResolveRequestDTO;
import com.firstclub.risk.entity.ReviewCaseStatus;
import com.firstclub.risk.review.ManualReviewEscalationService;
import com.firstclub.risk.scoring.RiskDecisionExplanation;
import com.firstclub.risk.scoring.RiskDecisionExplainer;
import com.firstclub.risk.scoring.RiskPostureSummary;
import com.firstclub.risk.scoring.RiskPostureService;
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

import java.util.Map;

/**
 * Phase 18 risk-review controller.
 *
 * <p>Provides endpoints for:
 * <ul>
 *   <li>Listing and resolving manual review cases (quick-approve / quick-reject)</li>
 *   <li>Manually escalating a specific case</li>
 *   <li>Triggering auto-escalation for all overdue cases</li>
 *   <li>Fetching the merchant risk posture summary</li>
 *   <li>Explaining a specific payment-intent risk decision</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v2/risk")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Risk Review (Phase 18)", description = "Risk score decay, posture and explainability endpoints")
public class RiskReviewController {

    private final ManualReviewService reviewService;
    private final ManualReviewEscalationService escalationService;
    private final RiskPostureService postureService;
    private final RiskDecisionExplainer decisionExplainer;

    // ── Manual review cases ──────────────────────────────────────────────────

    @GetMapping("/manual-reviews")
    @Operation(summary = "List manual review cases, optionally filtered by status")
    public ResponseEntity<Page<ManualReviewCaseResponseDTO>> listCases(
            @RequestParam(required = false) ReviewCaseStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(reviewService.listCases(status, pageable));
    }

    @PostMapping("/manual-reviews/{id}/approve")
    @Operation(summary = "Quick-approve a manual review case")
    public ResponseEntity<ManualReviewCaseResponseDTO> approveCase(
            @PathVariable Long id,
            @RequestParam(required = false) String note) {
        ManualReviewResolveRequestDTO req = new ManualReviewResolveRequestDTO(
                ReviewCaseStatus.APPROVED, note);
        return ResponseEntity.ok(reviewService.resolveCase(id, req));
    }

    @PostMapping("/manual-reviews/{id}/reject")
    @Operation(summary = "Quick-reject a manual review case")
    public ResponseEntity<ManualReviewCaseResponseDTO> rejectCase(
            @PathVariable Long id,
            @RequestParam(required = false) String note) {
        ManualReviewResolveRequestDTO req = new ManualReviewResolveRequestDTO(
                ReviewCaseStatus.REJECTED, note);
        return ResponseEntity.ok(reviewService.resolveCase(id, req));
    }

    @PostMapping("/manual-reviews/{id}/escalate")
    @Operation(summary = "Manually escalate a review case")
    public ResponseEntity<ManualReviewCaseResponseDTO> escalateCase(
            @PathVariable Long id,
            @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(escalationService.escalateCase(id, reason));
    }

    @PostMapping("/manual-reviews/escalate-overdue")
    @Operation(summary = "Auto-escalate all cases that have breached their SLA deadline")
    public ResponseEntity<Map<String, Integer>> escalateOverdue() {
        int count = escalationService.escalateOverdueCases();
        return ResponseEntity.ok(Map.of("escalatedCount", count));
    }

    // ── Posture & explainability ─────────────────────────────────────────────

    @GetMapping("/posture/{merchantId}")
    @Operation(summary = "Get the risk posture summary for a merchant")
    public ResponseEntity<RiskPostureSummary> getMerchantPosture(@PathVariable Long merchantId) {
        return ResponseEntity.ok(postureService.getPosture(merchantId));
    }

    @GetMapping("/decision-explanations/{paymentIntentId}")
    @Operation(summary = "Explain the most recent risk decision for a payment intent")
    public ResponseEntity<RiskDecisionExplanation> explainDecision(
            @PathVariable Long paymentIntentId) {
        return ResponseEntity.ok(decisionExplainer.explain(paymentIntentId));
    }
}
