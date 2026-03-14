package com.firstclub.payments.disputes.service.impl;

import com.firstclub.events.service.DomainEventLog;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.payments.capacity.DisputeCapacityService;
import com.firstclub.payments.capacity.PaymentCapacityInvariantService;
import com.firstclub.payments.disputes.dto.DisputeCreateRequestDTO;
import com.firstclub.payments.disputes.dto.DisputeResponseDTO;
import com.firstclub.payments.disputes.dto.DisputeResolveRequestDTO;
import com.firstclub.payments.disputes.entity.Dispute;
import com.firstclub.payments.disputes.entity.DisputeStatus;
import com.firstclub.payments.disputes.repository.DisputeRepository;
import com.firstclub.payments.disputes.service.DisputeAccountingService;
import com.firstclub.payments.disputes.service.DisputeService;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DisputeServiceImpl implements DisputeService {

    private static final Set<PaymentStatus>   DISPUTABLE_STATUSES =
            Set.of(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED);

    private static final List<DisputeStatus>  ACTIVE_STATUSES =
            List.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW);

    private final PaymentRepository              paymentRepository;
    private final DisputeRepository              disputeRepository;
    private final DisputeAccountingService       disputeAccountingService;
    private final DomainEventLog                 domainEventLog;
    private final DisputeCapacityService         disputeCapacityService;
    private final PaymentCapacityInvariantService invariantService;

    // ── openDispute ───────────────────────────────────────────────────────────

    @Override
    @Transactional
    public DisputeResponseDTO openDispute(Long merchantId, Long paymentId,
                                          DisputeCreateRequestDTO request) {
        // 1. Pessimistic write lock — serialises concurrent dispute opens on same payment
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new MembershipException(
                        "Payment not found: " + paymentId,
                        "PAYMENT_NOT_FOUND", HttpStatus.NOT_FOUND));

        // 2. Tenant isolation
        validateMerchantOwnership(merchantId, payment);

        // 3. One active dispute per payment — safe because Payment row is already locked,
        //    so any concurrent openDispute is blocked until this transaction commits.
        //    Checked before payment status so that a payment already in DISPUTED status
        //    (because of a prior open dispute) returns 409 CONFLICT rather than 422.
        if (disputeRepository.existsByPaymentIdAndStatusIn(paymentId, ACTIVE_STATUSES)) {
            throw new MembershipException(
                    "Payment " + paymentId + " already has an active dispute",
                    "ACTIVE_DISPUTE_EXISTS", HttpStatus.CONFLICT);
        }

        // 4. Only CAPTURED / PARTIALLY_REFUNDED payments can be disputed
        if (!DISPUTABLE_STATUSES.contains(payment.getStatus())) {
            throw new MembershipException(
                    "Payment " + paymentId + " has status " + payment.getStatus()
                    + " and cannot be disputed",
                    "PAYMENT_NOT_DISPUTABLE", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        // 5. Capacity check via DisputeCapacityService (replaces inline computation)
        disputeCapacityService.checkDisputeCapacity(payment, request.getAmount());

        // 6. Persist dispute (OPEN)
        Dispute dispute = disputeRepository.save(Dispute.builder()
                .merchantId(merchantId)
                .paymentId(paymentId)
                .customerId(request.getCustomerId())
                .amount(request.getAmount())
                .reasonCode(request.getReasonCode())
                .status(DisputeStatus.OPEN)
                .dueBy(request.getDueBy())
                .build());

        // 7. Reserve the disputed amount on the payment
        BigDecimal newDisputedAmount = payment.getDisputedAmount().add(request.getAmount());
        payment.setDisputedAmount(newDisputedAmount);
        payment.setNetAmount(payment.getCapturedAmount()
                .subtract(payment.getRefundedAmount())
                .subtract(newDisputedAmount));
        payment.setStatus(PaymentStatus.DISPUTED);
        invariantService.syncMinorUnitFields(payment);
        paymentRepository.save(payment);

        // 8. Post DR DISPUTE_RESERVE / CR PG_CLEARING
        disputeAccountingService.postDisputeOpen(dispute, payment);

        // 8a. Mark reserve as posted — prevents double-debit on retry or repair runs
        dispute.setReservePosted(true);
        disputeRepository.save(dispute);

        // 9. Audit trail
        domainEventLog.record("DISPUTE_OPENED", Map.of(
                "disputeId", dispute.getId(),
                "paymentId",  paymentId,
                "merchantId", merchantId,
                "amount",     dispute.getAmount().toPlainString()));

        log.info("Dispute {} OPENED — paymentId={}, merchantId={}, amount={}",
                dispute.getId(), paymentId, merchantId, request.getAmount());

        return toDto(dispute, payment.getStatus());
    }

    // ── getDisputeById ────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public DisputeResponseDTO getDisputeById(Long merchantId, Long disputeId) {
        Dispute dispute = loadAndValidate(merchantId, disputeId);
        Payment payment = paymentRepository.findById(dispute.getPaymentId())
                .orElseThrow(() -> new MembershipException(
                        "Parent payment not found", "PAYMENT_NOT_FOUND", HttpStatus.NOT_FOUND));
        return toDto(dispute, payment.getStatus());
    }

    // ── listDisputes ──────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<DisputeResponseDTO> listDisputes(Long merchantId, DisputeStatus status) {
        List<Dispute> disputes = (status != null)
                ? disputeRepository.findByMerchantIdAndStatus(merchantId, status)
                : disputeRepository.findByMerchantId(merchantId);
        return disputes.stream()
                .map(d -> {
                    Payment p  = paymentRepository.findById(d.getPaymentId()).orElse(null);
                    PaymentStatus ps = (p != null) ? p.getStatus() : null;
                    return toDto(d, ps);
                })
                .collect(Collectors.toList());
    }

    // ── listDisputesByPayment ─────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<DisputeResponseDTO> listDisputesByPayment(Long merchantId, Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new MembershipException(
                        "Payment not found: " + paymentId,
                        "PAYMENT_NOT_FOUND", HttpStatus.NOT_FOUND));
        validateMerchantOwnership(merchantId, payment);
        return disputeRepository.findByMerchantIdAndPaymentId(merchantId, paymentId)
                .stream()
                .map(d -> toDto(d, payment.getStatus()))
                .collect(Collectors.toList());
    }

    // ── moveToUnderReview ─────────────────────────────────────────────────────

    @Override
    @Transactional
    public DisputeResponseDTO moveToUnderReview(Long merchantId, Long disputeId) {
        // Phase 9: acquire Dispute row lock to prevent concurrent status transitions.
        Dispute dispute = loadAndValidateWithLock(merchantId, disputeId);
        if (dispute.getStatus() != DisputeStatus.OPEN) {
            throw new MembershipException(
                    "Dispute " + disputeId + " cannot transition from "
                    + dispute.getStatus() + " to UNDER_REVIEW",
                    "INVALID_DISPUTE_TRANSITION", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        dispute.setStatus(DisputeStatus.UNDER_REVIEW);
        disputeRepository.save(dispute);

        domainEventLog.record("DISPUTE_UNDER_REVIEW", Map.of(
                "disputeId", disputeId, "merchantId", merchantId));

        Payment payment = paymentRepository.findById(dispute.getPaymentId())
                .orElseThrow(() -> new MembershipException(
                        "Parent payment not found", "PAYMENT_NOT_FOUND", HttpStatus.NOT_FOUND));
        return toDto(dispute, payment.getStatus());
    }

    // ── resolveDispute ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public DisputeResponseDTO resolveDispute(Long merchantId, Long disputeId,
                                             DisputeResolveRequestDTO request) {
        // Phase 9 fix: acquire Dispute row lock FIRST so concurrent resolves are
        // serialised.  After T1 commits, T2 re-reads the Dispute with fresh data
        // (status = WON/LOST, resolutionPosted = true) and fails on the ACTIVE_STATUSES
        // check — eliminating the write-skew race that allowed double-posting of
        // resolution accounting.
        Dispute dispute = loadAndValidateWithLock(merchantId, disputeId);

        // Only OPEN or UNDER_REVIEW can be resolved
        if (!ACTIVE_STATUSES.contains(dispute.getStatus())) {
            throw new MembershipException(
                    "Dispute " + disputeId + " is already in a terminal state: " + dispute.getStatus(),
                    "DISPUTE_ALREADY_RESOLVED", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        // Validate outcome value
        DisputeStatus outcome;
        try {
            outcome = DisputeStatus.valueOf(request.getOutcome().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new MembershipException(
                    "Invalid dispute outcome: '" + request.getOutcome() + "'. Expected WON or LOST.",
                    "INVALID_DISPUTE_OUTCOME", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (outcome != DisputeStatus.WON && outcome != DisputeStatus.LOST) {
            throw new MembershipException(
                    "Invalid dispute outcome: '" + request.getOutcome() + "'. Expected WON or LOST.",
                    "INVALID_DISPUTE_OUTCOME", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        // Pessimistic write lock on payment for safe amount changes.
        // Lock order: Dispute → Payment (maintained consistently to prevent deadlocks).
        Payment payment = paymentRepository.findByIdForUpdate(dispute.getPaymentId())
                .orElseThrow(() -> new MembershipException(
                        "Parent payment not found", "PAYMENT_NOT_FOUND", HttpStatus.NOT_FOUND));

        // Belt-and-suspenders guard: resolution accounting must never be posted twice
        if (dispute.isResolutionPosted()) {
            throw new MembershipException(
                    "Dispute " + disputeId + " resolution accounting has already been posted",
                    "DISPUTE_RESOLUTION_ALREADY_POSTED", HttpStatus.CONFLICT);
        }

        // Transition dispute
        dispute.setStatus(outcome);
        dispute.setResolvedAt(LocalDateTime.now());
        disputeRepository.save(dispute);

        // Clear the disputed amount reservation
        BigDecimal releasedAmount    = dispute.getAmount();
        BigDecimal newDisputedAmount = payment.getDisputedAmount().subtract(releasedAmount);
        if (newDisputedAmount.compareTo(BigDecimal.ZERO) < 0) {
            newDisputedAmount = BigDecimal.ZERO;
        }
        payment.setDisputedAmount(newDisputedAmount);

        if (outcome == DisputeStatus.WON) {
            // Reserve is released — money returns to merchant
            payment.setNetAmount(payment.getCapturedAmount()
                    .subtract(payment.getRefundedAmount())
                    .subtract(newDisputedAmount));
            // Restore payment status
            PaymentStatus restored = payment.getRefundedAmount().compareTo(BigDecimal.ZERO) > 0
                    ? PaymentStatus.PARTIALLY_REFUNDED
                    : PaymentStatus.CAPTURED;
            payment.setStatus(restored);
            disputeAccountingService.postDisputeWon(dispute, payment);
        } else {
            // LOST — money is permanently gone; reduce capturedAmount so netAmount is honest
            BigDecimal newCaptured = payment.getCapturedAmount().subtract(releasedAmount);
            if (newCaptured.compareTo(BigDecimal.ZERO) < 0) newCaptured = BigDecimal.ZERO;
            payment.setCapturedAmount(newCaptured);
            payment.setNetAmount(newCaptured.subtract(payment.getRefundedAmount()).subtract(newDisputedAmount));
            payment.setStatus(PaymentStatus.DISPUTED); // terminal state for a lost chargeback
            disputeAccountingService.postDisputeLost(dispute, payment);
        }
        invariantService.syncMinorUnitFields(payment);
        paymentRepository.save(payment);

        // Mark resolution as posted — prevents double-DR on retry or repair run
        dispute.setResolutionPosted(true);
        disputeRepository.save(dispute);

        domainEventLog.record("DISPUTE_RESOLVED", Map.of(
                "disputeId", disputeId,
                "merchantId", merchantId,
                "outcome", outcome.name()));

        log.info("Dispute {} resolved as {} — paymentId={}, merchantId={}, amount={}",
                disputeId, outcome, dispute.getPaymentId(), merchantId, dispute.getAmount());

        return toDto(dispute, payment.getStatus());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Non-locking read — for read-only operations. */
    private Dispute loadAndValidate(Long merchantId, Long disputeId) {
        return disputeRepository.findByMerchantIdAndId(merchantId, disputeId)
                .orElseThrow(() -> new MembershipException(
                        "Dispute not found: " + disputeId,
                        "DISPUTE_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    /**
     * Locking read — for write operations.  Acquires a pessimistic write lock
     * on the Dispute row and then validates merchant ownership.
     *
     * <p>Using a lock here ensures concurrent writes (e.g., two concurrent
     * {@code resolveDispute} calls) are serialised: the second caller re-reads
     * the freshly-committed state of the dispute row after the first commits.
     */
    private Dispute loadAndValidateWithLock(Long merchantId, Long disputeId) {
        Dispute dispute = disputeRepository.findByIdForUpdate(disputeId)
                .orElseThrow(() -> new MembershipException(
                        "Dispute not found: " + disputeId,
                        "DISPUTE_NOT_FOUND", HttpStatus.NOT_FOUND));
        validateDisputeMerchantOwnership(merchantId, dispute);
        return dispute;
    }

    private void validateMerchantOwnership(Long merchantId, Payment payment) {
        if (payment.getMerchantId() == null) {
            throw new MembershipException(
                    "Payment " + payment.getId() + " has no merchant association",
                    "PAYMENT_MERCHANT_UNRESOLVED", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!merchantId.equals(payment.getMerchantId())) {
            throw new MembershipException(
                    "Payment " + payment.getId() + " does not belong to merchant " + merchantId,
                    "PAYMENT_MERCHANT_MISMATCH", HttpStatus.FORBIDDEN);
        }
    }

    private void validateDisputeMerchantOwnership(Long merchantId, Dispute dispute) {
        if (!merchantId.equals(dispute.getMerchantId())) {
            throw new MembershipException(
                    "Dispute " + dispute.getId() + " does not belong to merchant " + merchantId,
                    "DISPUTE_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
    }

    private DisputeResponseDTO toDto(Dispute dispute, PaymentStatus paymentStatus) {
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
    }
}
