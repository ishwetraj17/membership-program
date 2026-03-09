package com.firstclub.payments.disputes.service.impl;

import com.firstclub.payments.disputes.dto.DisputeResponseDTO;
import com.firstclub.payments.disputes.entity.DisputeStatus;
import com.firstclub.payments.disputes.repository.DisputeRepository;
import com.firstclub.payments.disputes.service.DisputeDueDateCheckerService;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Phase 15 — Finds disputes approaching their evidence deadline.
 *
 * <p>The query is intentionally cross-merchant (admin use-case) so that
 * operations teams can triage all near-deadline disputes in one view.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DisputeDueDateCheckerServiceImpl implements DisputeDueDateCheckerService {

    private static final List<DisputeStatus> ACTIVE_STATUSES =
            List.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW);

    private final DisputeRepository disputeRepository;
    private final PaymentRepository paymentRepository;

    @Override
    @Transactional(readOnly = true)
    public List<DisputeResponseDTO> findDueSoon(int withinDays) {
        LocalDateTime cutoff = LocalDateTime.now().plusDays(withinDays);

        log.debug("DisputeDueDateChecker: looking for disputes with dueBy < {} (withinDays={})", cutoff, withinDays);

        return disputeRepository.findByStatusInAndDueByBefore(ACTIVE_STATUSES, cutoff)
                .stream()
                .sorted(Comparator.comparing(
                        d -> d.getDueBy() == null ? LocalDateTime.MAX : d.getDueBy()))
                .map(dispute -> {
                    PaymentStatus paymentStatus = paymentRepository
                            .findById(dispute.getPaymentId())
                            .map(p -> p.getStatus())
                            .orElse(null);
                    return DisputeResponseDTO.builder()
                            .id(dispute.getId())
                            .merchantId(dispute.getMerchantId())
                            .paymentId(dispute.getPaymentId())
                            .customerId(dispute.getCustomerId())
                            .amount(dispute.getAmount())
                            .reasonCode(dispute.getReasonCode())
                            .status(dispute.getStatus())
                            .openedAt(dispute.getOpenedAt())
                            .dueBy(dispute.getDueBy())
                            .resolvedAt(dispute.getResolvedAt())
                            .paymentStatusAfter(paymentStatus)
                            .reservePosted(dispute.isReservePosted())
                            .resolutionPosted(dispute.isResolutionPosted())
                            .build();
                })
                .collect(Collectors.toList());
    }
}
