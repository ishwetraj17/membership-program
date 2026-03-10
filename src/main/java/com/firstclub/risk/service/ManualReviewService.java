package com.firstclub.risk.service;

import com.firstclub.risk.dto.ManualReviewCaseResponseDTO;
import com.firstclub.risk.dto.ManualReviewResolveRequestDTO;
import com.firstclub.risk.entity.ManualReviewCase;
import com.firstclub.risk.entity.ReviewCaseStatus;
import com.firstclub.risk.repository.ManualReviewCaseRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Manages the manual review queue for payment intents flagged by the risk engine.
 *
 * <h3>Lifecycle</h3>
 * <pre>
 *   OPEN ──→ APPROVED | REJECTED | ESCALATED | CLOSED
 *   ESCALATED ──→ APPROVED | REJECTED | CLOSED
 *   APPROVED, REJECTED, CLOSED  (terminal — no further transitions)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualReviewService {

    private final ManualReviewCaseRepository caseRepository;

    /** Default SLA window: 24 hours from case creation. */
    private static final int SLA_HOURS = 24;

    // ── Internal creation (called by RiskDecisionService) ─────────────────────

    @Transactional
    public ManualReviewCase createCase(Long merchantId, Long paymentIntentId, Long customerId) {
        ManualReviewCase reviewCase = ManualReviewCase.builder()
                .merchantId(merchantId)
                .paymentIntentId(paymentIntentId)
                .customerId(customerId)
                .status(ReviewCaseStatus.OPEN)
                .slaDueAt(LocalDateTime.now().plusHours(SLA_HOURS))   // Phase 18: SLA deadline
                .build();
        ManualReviewCase saved = caseRepository.save(reviewCase);
        log.info("Manual review case {} created for intent={} merchant={} slaDueAt={}",
                saved.getId(), paymentIntentId, merchantId, saved.getSlaDueAt());
        return saved;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<ManualReviewCaseResponseDTO> listCases(ReviewCaseStatus status, Pageable pageable) {
        Page<ManualReviewCase> page = (status != null)
                ? caseRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
                : caseRepository.findAllByOrderByCreatedAtDesc(pageable);
        return page.map(ManualReviewCaseResponseDTO::from);
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    @Transactional
    public ManualReviewCaseResponseDTO assignCase(Long caseId, Long userId) {
        ManualReviewCase reviewCase = loadCase(caseId);
        ensureNotTerminal(reviewCase.getStatus());
        reviewCase.setAssignedTo(userId);
        log.info("Manual review case {} assigned to user {}", caseId, userId);
        return ManualReviewCaseResponseDTO.from(caseRepository.save(reviewCase));
    }

    @Transactional
    public ManualReviewCaseResponseDTO resolveCase(Long caseId, ManualReviewResolveRequestDTO request) {
        ManualReviewCase reviewCase = loadCase(caseId);
        validateTransition(reviewCase.getStatus(), request.getResolution());
        reviewCase.setStatus(request.getResolution());
        // Phase 18: stamp close audit fields
        if (terminalStatuses().contains(request.getResolution())) {
            reviewCase.setClosedAt(LocalDateTime.now());
            reviewCase.setDecisionReason(request.getNote());
        }
        if (request.getResolution() == ReviewCaseStatus.ESCALATED) {
            reviewCase.setEscalatedAt(LocalDateTime.now());
        }
        ManualReviewCase updated = caseRepository.save(reviewCase);
        log.info("Manual review case {} resolved → {} for intent={}",
                caseId, request.getResolution(), reviewCase.getPaymentIntentId());
        return ManualReviewCaseResponseDTO.from(updated);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private ManualReviewCase loadCase(Long caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new EntityNotFoundException("ManualReviewCase not found: " + caseId));
    }

    private void ensureNotTerminal(ReviewCaseStatus status) {
        if (terminalStatuses().contains(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot modify case in terminal status: " + status);
        }
    }

    private void validateTransition(ReviewCaseStatus current, ReviewCaseStatus target) {
        Set<ReviewCaseStatus> allowed = switch (current) {
            case OPEN      -> Set.of(ReviewCaseStatus.APPROVED, ReviewCaseStatus.REJECTED,
                                     ReviewCaseStatus.ESCALATED, ReviewCaseStatus.CLOSED);
            case ESCALATED -> Set.of(ReviewCaseStatus.APPROVED, ReviewCaseStatus.REJECTED,
                                     ReviewCaseStatus.CLOSED);
            default        -> throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Case is in terminal status: " + current);
        };
        if (!allowed.contains(target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Invalid transition: " + current + " → " + target);
        }
    }

    private static Set<ReviewCaseStatus> terminalStatuses() {
        return Set.of(ReviewCaseStatus.APPROVED, ReviewCaseStatus.REJECTED, ReviewCaseStatus.CLOSED);
    }
}
