package com.firstclub.payments.service;

import com.firstclub.membership.exception.MembershipException;
import com.firstclub.payments.dto.PaymentIntentDTO;
import com.firstclub.payments.entity.PaymentIntent;
import com.firstclub.payments.model.PaymentIntentStatus;
import com.firstclub.payments.repository.PaymentIntentRepository;
import com.firstclub.platform.statemachine.StateMachineValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentIntentService {

    private final PaymentIntentRepository repository;
    private final StateMachineValidator stateMachineValidator;

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    /**
     * Creates a new PaymentIntent in {@code REQUIRES_PAYMENT_METHOD} state.
     *
     * @param invoiceId optional invoice this payment covers (may be null)
     */
    @Transactional
    public PaymentIntentDTO createForInvoice(Long invoiceId, BigDecimal amount, String currency) {
        PaymentIntent pi = PaymentIntent.builder()
                .invoiceId(invoiceId)
                .amount(amount)
                .currency(currency)
                .status(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD)
                .clientSecret("cs_" + compact(UUID.randomUUID()))
                .gatewayReference("gw_" + compact(UUID.randomUUID()))
                .build();
        return toDto(repository.save(pi));
    }

    // -------------------------------------------------------------------------
    // State transitions
    // -------------------------------------------------------------------------

    /**
     * Transitions the PaymentIntent to {@code PROCESSING}.
     * If the intent is still in {@code REQUIRES_PAYMENT_METHOD}, it is
     * automatically advanced through {@code REQUIRES_CONFIRMATION} first
     * (matching the state-machine rules).
     */
    @Transactional
    public void markProcessing(Long id) {
        PaymentIntent pi = findEntityById(id);
        if (pi.getStatus() == PaymentIntentStatus.REQUIRES_PAYMENT_METHOD) {
            stateMachineValidator.validate("PAYMENT_INTENT", pi.getStatus(),
                    PaymentIntentStatus.REQUIRES_CONFIRMATION);
            pi.setStatus(PaymentIntentStatus.REQUIRES_CONFIRMATION);
        }
        stateMachineValidator.validate("PAYMENT_INTENT", pi.getStatus(), PaymentIntentStatus.PROCESSING);
        pi.setStatus(PaymentIntentStatus.PROCESSING);
        repository.save(pi);
        log.debug("PaymentIntent {} → PROCESSING", id);
    }

    /**
     * Transitions to {@code REQUIRES_ACTION} (e.g. 3-DS / OTP challenge).
     * Advances through {@code REQUIRES_CONFIRMATION} automatically if needed.
     */
    @Transactional
    public void markRequiresAction(Long id) {
        PaymentIntent pi = findEntityById(id);
        if (pi.getStatus() == PaymentIntentStatus.REQUIRES_PAYMENT_METHOD) {
            stateMachineValidator.validate("PAYMENT_INTENT", pi.getStatus(),
                    PaymentIntentStatus.REQUIRES_CONFIRMATION);
            pi.setStatus(PaymentIntentStatus.REQUIRES_CONFIRMATION);
        }
        stateMachineValidator.validate("PAYMENT_INTENT", pi.getStatus(), PaymentIntentStatus.REQUIRES_ACTION);
        pi.setStatus(PaymentIntentStatus.REQUIRES_ACTION);
        repository.save(pi);
        log.debug("PaymentIntent {} → REQUIRES_ACTION", id);
    }

    @Transactional
    public void markSucceeded(Long id) {
        PaymentIntent pi = findEntityById(id);
        stateMachineValidator.validate("PAYMENT_INTENT", pi.getStatus(), PaymentIntentStatus.SUCCEEDED);
        pi.setStatus(PaymentIntentStatus.SUCCEEDED);
        repository.save(pi);
        log.info("PaymentIntent {} → SUCCEEDED", id);
    }

    @Transactional
    public void markFailed(Long id) {
        PaymentIntent pi = findEntityById(id);
        stateMachineValidator.validate("PAYMENT_INTENT", pi.getStatus(), PaymentIntentStatus.FAILED);
        pi.setStatus(PaymentIntentStatus.FAILED);
        repository.save(pi);
        log.info("PaymentIntent {} → FAILED", id);
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PaymentIntentDTO findById(Long id) {
        return toDto(findEntityById(id));
    }

    // Also used by GatewaySimulatorService and GatewayController for entity-level access.
    public PaymentIntent findEntityById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new MembershipException(
                        "PaymentIntent not found: " + id,
                        "PAYMENT_INTENT_NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    private PaymentIntentDTO toDto(PaymentIntent pi) {
        return PaymentIntentDTO.builder()
                .id(pi.getId())
                .invoiceId(pi.getInvoiceId())
                .amount(pi.getAmount())
                .currency(pi.getCurrency())
                .status(pi.getStatus())
                .clientSecret(pi.getClientSecret())
                .gatewayReference(pi.getGatewayReference())
                .createdAt(pi.getCreatedAt())
                .updatedAt(pi.getUpdatedAt())
                .build();
    }

    private static String compact(UUID uuid) {
        return uuid.toString().replace("-", "");
    }
}
