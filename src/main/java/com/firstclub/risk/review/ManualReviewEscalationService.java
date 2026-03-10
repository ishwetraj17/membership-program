package com.firstclub.risk.review;

import com.firstclub.risk.dto.ManualReviewCaseResponseDTO;
import com.firstclub.risk.entity.ManualReviewCase;
import com.firstclub.risk.entity.ReviewCaseStatus;
import com.firstclub.risk.repository.ManualReviewCaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Handles escalation of manual review cases — both automated (SLA breach) and
 * manual (operator-triggered).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualReviewEscalationService {

    private final ManualReviewCaseRepository caseRepository;

    /**
     * Automatically escalate any case that has breached its SLA deadline and is
     * still in OPEN status. Stamps {@code escalatedAt} on each affected case.
     *
     * @return number of cases escalated
     */
    @Transactional
    public int escalateOverdueCases() {
        LocalDateTime now = LocalDateTime.now();
        List<ManualReviewCase> overdue = caseRepository.findOverdueCases(now);

        for (ManualReviewCase reviewCase : overdue) {
            reviewCase.setStatus(ReviewCaseStatus.ESCALATED);
            reviewCase.setEscalatedAt(now);
        }

        if (!overdue.isEmpty()) {
            caseRepository.saveAll(overdue);
            log.warn("Escalated {} overdue manual review cases", overdue.size());
        }
        return overdue.size();
    }

    /**
     * Manually escalate a specific case, providing a reason that is stored in
     * {@code decisionReason}.
     *
     * @param caseId the ID of the case to escalate
     * @param reason free-text reason for escalation (optional)
     * @return the updated case DTO
     * @throws ResponseStatusException 404 if not found, 409 if already terminal
     */
    @Transactional
    public ManualReviewCaseResponseDTO escalateCase(Long caseId, String reason) {
        ManualReviewCase reviewCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "ManualReviewCase not found: " + caseId));

        if (isTerminal(reviewCase.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot escalate case in terminal status: " + reviewCase.getStatus());
        }
        if (reviewCase.getStatus() == ReviewCaseStatus.ESCALATED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Case is already escalated: " + caseId);
        }

        LocalDateTime now = LocalDateTime.now();
        reviewCase.setStatus(ReviewCaseStatus.ESCALATED);
        reviewCase.setEscalatedAt(now);
        if (reason != null && !reason.isBlank()) {
            reviewCase.setDecisionReason(reason);
        }

        ManualReviewCase saved = caseRepository.save(reviewCase);
        log.info("Manual review case {} manually escalated: {}", caseId, reason);
        return ManualReviewCaseResponseDTO.from(saved);
    }

    private boolean isTerminal(ReviewCaseStatus status) {
        return status == ReviewCaseStatus.APPROVED
                || status == ReviewCaseStatus.REJECTED
                || status == ReviewCaseStatus.CLOSED;
    }
}
