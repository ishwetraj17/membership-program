package com.firstclub.payments.disputes.service;

import com.firstclub.payments.disputes.dto.DisputeEvidenceCreateRequestDTO;
import com.firstclub.payments.disputes.dto.DisputeEvidenceResponseDTO;

import java.util.List;

/**
 * Manages evidence submissions for an open dispute.
 *
 * <p>Evidence is immutable after upload — once created it cannot be edited or deleted.
 * Submission is blocked after the dispute's {@code due_by} deadline
 * ({@code EVIDENCE_DEADLINE_PASSED → 422}).
 */
public interface DisputeEvidenceService {

    /**
     * Add a piece of evidence to an active dispute.
     *
     * @throws com.firstclub.membership.exception.MembershipException DISPUTE_NOT_FOUND (404)
     * @throws com.firstclub.membership.exception.MembershipException EVIDENCE_DEADLINE_PASSED (422)
     */
    DisputeEvidenceResponseDTO addEvidence(Long merchantId, Long disputeId,
                                           DisputeEvidenceCreateRequestDTO request);

    /**
     * List all evidence for a dispute, ordered by submission time (oldest first).
     *
     * @throws com.firstclub.membership.exception.MembershipException DISPUTE_NOT_FOUND (404)
     */
    List<DisputeEvidenceResponseDTO> listEvidence(Long merchantId, Long disputeId);
}
