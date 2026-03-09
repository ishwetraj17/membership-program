package com.firstclub.payments.disputes.service.impl;

import com.firstclub.membership.exception.MembershipException;
import com.firstclub.payments.disputes.dto.DisputeEvidenceCreateRequestDTO;
import com.firstclub.payments.disputes.dto.DisputeEvidenceResponseDTO;
import com.firstclub.payments.disputes.entity.Dispute;
import com.firstclub.payments.disputes.entity.DisputeEvidence;
import com.firstclub.payments.disputes.repository.DisputeEvidenceRepository;
import com.firstclub.payments.disputes.repository.DisputeRepository;
import com.firstclub.payments.disputes.service.DisputeEvidenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DisputeEvidenceServiceImpl implements DisputeEvidenceService {

    private final DisputeRepository         disputeRepository;
    private final DisputeEvidenceRepository evidenceRepository;

    @Override
    @Transactional
    public DisputeEvidenceResponseDTO addEvidence(Long merchantId, Long disputeId,
                                                   DisputeEvidenceCreateRequestDTO request) {
        Dispute dispute = loadDispute(merchantId, disputeId);

        // Deadline enforcement — evidence only accepted before due_by (if set)
        if (dispute.getDueBy() != null && LocalDateTime.now().isAfter(dispute.getDueBy())) {
            throw new MembershipException(
                    "Evidence submission deadline has passed for dispute " + disputeId,
                    "EVIDENCE_DEADLINE_PASSED", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        DisputeEvidence evidence = evidenceRepository.save(DisputeEvidence.builder()
                .disputeId(disputeId)
                .evidenceType(request.getEvidenceType())
                .contentReference(request.getContentReference())
                .uploadedBy(request.getUploadedBy())
                .build());

        log.info("Evidence {} added to dispute {} by user {}",
                evidence.getId(), disputeId, request.getUploadedBy());

        return toDto(evidence);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DisputeEvidenceResponseDTO> listEvidence(Long merchantId, Long disputeId) {
        loadDispute(merchantId, disputeId); // validates tenant ownership
        return evidenceRepository.findByDisputeIdOrderByCreatedAtAsc(disputeId)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private Dispute loadDispute(Long merchantId, Long disputeId) {
        return disputeRepository.findByMerchantIdAndId(merchantId, disputeId)
                .orElseThrow(() -> new MembershipException(
                        "Dispute not found: " + disputeId,
                        "DISPUTE_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    private DisputeEvidenceResponseDTO toDto(DisputeEvidence e) {
        return DisputeEvidenceResponseDTO.builder()
                .id(e.getId())
                .disputeId(e.getDisputeId())
                .evidenceType(e.getEvidenceType())
                .contentReference(e.getContentReference())
                .uploadedBy(e.getUploadedBy())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
