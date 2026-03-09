package com.firstclub.payments.recovery;

import com.firstclub.payments.entity.FailureCategory;
import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentAttemptStatus;
import com.firstclub.payments.entity.PaymentIntentStatusV2;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.gateway.GatewayResult;
import com.firstclub.payments.gateway.GatewayStatusResolver;
import com.firstclub.payments.repository.PaymentAttemptRepository;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
import com.firstclub.payments.service.PaymentAttemptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Resolves payment attempts that ended in an UNKNOWN outcome (e.g. gateway timeout).
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>Each {@link #reconcile} call is wrapped in its own {@code REQUIRES_NEW} transaction
 *       so a failure on one attempt does not roll back others in the same batch.</li>
 *   <li>After marking an attempt SUCCEEDED the {@code lastSuccessfulAttemptId} on the parent
 *       intent is updated and the intent is driven to SUCCEEDED.</li>
 *   <li>The single-success invariant is enforced by
 *       {@link PaymentAttemptService#markSucceeded}; a second call for the same intent will
 *       throw {@code PaymentIntentException.alreadySucceeded}.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentOutcomeReconciler {

    private final GatewayStatusResolver gatewayStatusResolver;
    private final PaymentAttemptService paymentAttemptService;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentIntentV2Repository paymentIntentV2Repository;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Attempt to resolve a single UNKNOWN attempt by querying the gateway.
     *
     * <p>Runs in its own transaction ({@code REQUIRES_NEW}) so scheduler-level failures
     * on neighbouring attempts do not roll this one back.</p>
     *
     * <p>Resolution outcomes:</p>
     * <ul>
     *   <li><b>Gateway says SUCCEEDED</b> → attempt marked SUCCEEDED; intent driven
     *       to SUCCEEDED and {@code lastSuccessfulAttemptId} set.</li>
     *   <li><b>Gateway says FAILED</b> → attempt marked FAILED; intent left for
     *       retry or manual intervention.</li>
     *   <li><b>Gateway still UNKNOWN / TIMEOUT</b> → attempt marked RECONCILED
     *       (manual review state); intent {@code reconciliationState} set to
     *       {@code "REQUIRES_MANUAL_REVIEW"}.</li>
     * </ul>
     *
     * @param attempt a UNKNOWN-status {@link PaymentAttempt}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcile(PaymentAttempt attempt) {
        if (attempt.getStatus() != PaymentAttemptStatus.UNKNOWN) {
            log.debug("Skipping reconcile for attempt {} — status is {}",
                    attempt.getId(), attempt.getStatus());
            return;
        }

        Long intentId = attempt.getPaymentIntent().getId();
        log.info("Reconciling UNKNOWN attempt {} for intent {}", attempt.getId(), intentId);

        GatewayResult resolved = gatewayStatusResolver.resolveStatus(attempt);

        if (resolved.isSucceeded()) {
            paymentAttemptService.markSucceeded(
                    attempt.getId(), intentId, resolved.responseCode(), resolved.latencyMs());

            paymentIntentV2Repository.findById(intentId).ifPresent(intent -> {
                intent.setLastSuccessfulAttemptId(attempt.getId());
                intent.setStatus(PaymentIntentStatusV2.SUCCEEDED);
                intent.setReconciliationState("RECONCILED_SUCCESS");
                paymentIntentV2Repository.save(intent);
            });

            log.info("Reconciled attempt {} SUCCEEDED for intent {}", attempt.getId(), intentId);

        } else if (resolved.isFailed()) {
            paymentAttemptService.markFailed(
                    attempt.getId(), intentId,
                    resolved.responseCode(), resolved.responseMessage(),
                    resolved.failureCategory() != null
                            ? resolved.failureCategory()
                            : FailureCategory.GATEWAY_ERROR,
                    /* retriable */ false,
                    resolved.latencyMs());

            paymentIntentV2Repository.findById(intentId).ifPresent(intent -> {
                intent.setReconciliationState("RECONCILED_FAILED");
                paymentIntentV2Repository.save(intent);
            });

            log.info("Reconciled attempt {} FAILED for intent {}", attempt.getId(), intentId);

        } else {
            // Still UNKNOWN / TIMEOUT after polling — mark RECONCILED for manual review
            markReconciled(attempt);

            paymentIntentV2Repository.findById(intentId).ifPresent(intent -> {
                intent.setReconciliationState("REQUIRES_MANUAL_REVIEW");
                paymentIntentV2Repository.save(intent);
            });

            log.warn("Attempt {} for intent {} could not be resolved — marked RECONCILED (manual review)",
                    attempt.getId(), intentId);
        }
    }

    /**
     * Trigger reconciliation for all UNKNOWN attempts on a given intent.
     * Useful when an operator manually requests reconciliation via the API.
     *
     * @return number of attempts processed
     */
    @Transactional(readOnly = true)
    public int reconcileIntent(Long intentId) {
        List<PaymentAttempt> unknownAttempts =
                paymentAttemptRepository.findByPaymentIntentIdAndStatus(
                        intentId, PaymentAttemptStatus.UNKNOWN);

        log.info("reconcileIntent: {} UNKNOWN attempt(s) found for intent {}",
                unknownAttempts.size(), intentId);

        // Each reconcile runs in REQUIRES_NEW — call individually
        for (PaymentAttempt attempt : unknownAttempts) {
            reconcile(attempt);
        }
        return unknownAttempts.size();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void markReconciled(PaymentAttempt attempt) {
        attempt.setStatus(PaymentAttemptStatus.RECONCILED);
        attempt.setCompletedAt(LocalDateTime.now());
        paymentAttemptRepository.save(attempt);
    }
}
