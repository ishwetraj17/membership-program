package com.firstclub.payments.service.impl;

import com.firstclub.payments.dto.PaymentAttemptResponseDTO;
import com.firstclub.payments.entity.FailureCategory;
import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentAttemptStatus;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.exception.PaymentIntentException;
import com.firstclub.payments.mapper.PaymentAttemptMapper;
import com.firstclub.payments.repository.PaymentAttemptRepository;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
import com.firstclub.payments.service.PaymentAttemptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PaymentAttemptServiceImpl implements PaymentAttemptService {

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentIntentV2Repository paymentIntentV2Repository;
    private final PaymentAttemptMapper paymentAttemptMapper;

    @Override
    public PaymentAttempt createAttempt(PaymentIntentV2 intent, int attemptNumber,
                                         String gatewayName) {
        PaymentAttempt attempt = PaymentAttempt.builder()
                .paymentIntent(intent)
                .attemptNumber(attemptNumber)
                .gatewayName(gatewayName)
                .build();
        PaymentAttempt saved = paymentAttemptRepository.save(attempt);
        log.info("Created attempt #{} for payment intent {}", attemptNumber, intent.getId());
        return saved;
    }

    @Override
    public PaymentAttempt markAuthorized(Long attemptId, Long intentId,
                                          String gatewayReference) {
        PaymentAttempt attempt = loadAttempt(attemptId, intentId);
        guardNotTerminal(attempt);
        attempt.setStatus(PaymentAttemptStatus.AUTHORIZED);
        attempt.setGatewayReference(gatewayReference);
        return paymentAttemptRepository.save(attempt);
    }

    @Override
    public PaymentAttempt markCaptured(Long attemptId, Long intentId,
                                        String responseCode, Long latencyMs) {
        PaymentAttempt attempt = loadAttempt(attemptId, intentId);
        guardNotTerminal(attempt);
        attempt.setStatus(PaymentAttemptStatus.CAPTURED);
        attempt.setResponseCode(responseCode);
        attempt.setLatencyMs(latencyMs);
        attempt.setCompletedAt(LocalDateTime.now());
        return paymentAttemptRepository.save(attempt);
    }

    @Override
    public PaymentAttempt markFailed(Long attemptId, Long intentId,
                                      String responseCode, String responseMessage,
                                      FailureCategory failureCategory, boolean retriable,
                                      Long latencyMs) {
        PaymentAttempt attempt = loadAttempt(attemptId, intentId);
        guardNotTerminal(attempt);
        attempt.setStatus(PaymentAttemptStatus.FAILED);
        attempt.setResponseCode(responseCode);
        attempt.setResponseMessage(responseMessage);
        attempt.setFailureCategory(failureCategory);
        attempt.setRetriable(retriable);
        attempt.setLatencyMs(latencyMs);
        attempt.setCompletedAt(LocalDateTime.now());
        return paymentAttemptRepository.save(attempt);
    }

    @Override
    public PaymentAttempt markSucceeded(Long attemptId, Long intentId,
                                         String responseCode, Long latencyMs) {
        // Enforce exactly-one-SUCCEEDED-per-intent invariant (prevents duplicate charges)
        int existingSuccess = paymentAttemptRepository
                .countByPaymentIntentIdAndStatus(intentId, PaymentAttemptStatus.SUCCEEDED);
        if (existingSuccess > 0) {
            throw PaymentIntentException.alreadySucceeded(intentId);
        }
        PaymentAttempt attempt = loadAttempt(attemptId, intentId);
        guardNotTerminal(attempt);
        attempt.setStatus(PaymentAttemptStatus.SUCCEEDED);
        attempt.setResponseCode(responseCode);
        attempt.setLatencyMs(latencyMs);
        attempt.setCompletedAt(LocalDateTime.now());
        return paymentAttemptRepository.save(attempt);
    }

    @Override
    public PaymentAttempt markUnknown(Long attemptId, Long intentId, Long latencyMs) {
        PaymentAttempt attempt = loadAttempt(attemptId, intentId);
        guardNotTerminal(attempt);
        attempt.setStatus(PaymentAttemptStatus.UNKNOWN);
        attempt.setLatencyMs(latencyMs);
        // completedAt intentionally left null — UNKNOWN is not a terminal state
        return paymentAttemptRepository.save(attempt);
    }

    @Override
    public PaymentAttempt markReconciled(Long attemptId, Long intentId) {
        PaymentAttempt attempt = loadAttempt(attemptId, intentId);
        guardNotTerminal(attempt);
        attempt.setStatus(PaymentAttemptStatus.RECONCILED);
        attempt.setCompletedAt(LocalDateTime.now());
        return paymentAttemptRepository.save(attempt);
    }

    @Override
    public PaymentAttempt markRequiresAction(Long attemptId, Long intentId) {
        PaymentAttempt attempt = loadAttempt(attemptId, intentId);
        guardNotTerminal(attempt);
        attempt.setStatus(PaymentAttemptStatus.REQUIRES_ACTION);
        return paymentAttemptRepository.save(attempt);
    }

    @Override
    public int computeNextAttemptNumber(Long paymentIntentId) {
        // Use MAX rather than COUNT: correct even when attempt numbers are non-contiguous.
        // Guard: BusinessLockScope.PAYMENT_ATTEMPT_NUMBERING — see PaymentAttemptRepository.
        return paymentAttemptRepository.findMaxAttemptNumberByPaymentIntentId(paymentIntentId) + 1;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentAttemptResponseDTO> listByPaymentIntent(Long merchantId, Long intentId) {
        // Verify the intent belongs to the merchant before exposing attempts
        paymentIntentV2Repository.findByMerchantIdAndId(merchantId, intentId)
                .orElseThrow(() -> PaymentIntentException.notFound(merchantId, intentId));
        return paymentAttemptRepository
                .findByPaymentIntentIdOrderByAttemptNumberAsc(intentId)
                .stream()
                .map(paymentAttemptMapper::toResponseDTO)
                .toList();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private PaymentAttempt loadAttempt(Long attemptId, Long intentId) {
        return paymentAttemptRepository.findByIdAndPaymentIntentId(attemptId, intentId)
                .orElseThrow(() -> PaymentIntentException.attemptNotFound(attemptId, intentId));
    }

    private static void guardNotTerminal(PaymentAttempt attempt) {
        if (attempt.getStatus().isTerminal()) {
            throw PaymentIntentException.attemptImmutable(attempt.getId());
        }
    }
}
