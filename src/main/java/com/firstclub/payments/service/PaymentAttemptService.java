package com.firstclub.payments.service;

import com.firstclub.payments.dto.PaymentAttemptResponseDTO;
import com.firstclub.payments.entity.FailureCategory;
import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentIntentV2;

import java.util.List;

/**
 * Service managing the lifecycle of individual payment gateway attempts.
 *
 * <p>Each call to {@link #createAttempt} allocates a new {@link PaymentAttempt}
 * in STARTED state. The subsequent mark-* methods advance the attempt through
 * its state machine; attempts are immutable once they reach a terminal state
 * (CAPTURED, SUCCEEDED, FAILED, TIMEOUT, RECONCILED, or CANCELLED).</p>
 *
 * <h3>Phase 8 additions</h3>
 * <ul>
 *   <li>{@link #markUnknown} — gateway timed out; outcome unresolvable right now.</li>
 *   <li>{@link #markSucceeded} — Phase 8 terminal success (supersedes markCaptured for
 *       new confirm flows); enforces single-success-per-intent invariant.</li>
 *   <li>{@link #markReconciled} — UNKNOWN resolved via async gateway status check;  
 *       used directly by {@code PaymentOutcomeReconciler} when outcome is still
 *       indeterminate after polling.</li>
 * </ul>
 */
public interface PaymentAttemptService {

    /** Allocate a new STARTED attempt for the given payment intent. */
    PaymentAttempt createAttempt(PaymentIntentV2 intent, int attemptNumber, String gatewayName);

    /** Transition to AUTHORIZED; record gateway's pre-auth reference. */
    PaymentAttempt markAuthorized(Long attemptId, Long intentId, String gatewayReference);

    /** Transition to CAPTURED; record response code and round-trip latency. */
    PaymentAttempt markCaptured(Long attemptId, Long intentId, String responseCode, Long latencyMs);

    /**
     * Transition to SUCCEEDED (Phase 8 terminal success path).
     *
     * <p><b>Single-success invariant:</b> throws
     * {@code PaymentIntentException.alreadySucceeded} if the intent already has a
     * SUCCEEDED attempt, preventing double-charge via duplicate gateway callbacks.</p>
     */
    PaymentAttempt markSucceeded(Long attemptId, Long intentId, String responseCode, Long latencyMs);

    /**
     * Transition to FAILED; record failure details and whether a retry is safe.
     *
     * @param retriable {@code true} if the caller should spawn a new attempt on the same intent
     */
    PaymentAttempt markFailed(Long attemptId, Long intentId, String responseCode,
                               String responseMessage, FailureCategory failureCategory,
                               boolean retriable, Long latencyMs);

    /** Transition to REQUIRES_ACTION (e.g. 3-D Secure redirect needed). */
    PaymentAttempt markRequiresAction(Long attemptId, Long intentId);

    /**
     * Transition to UNKNOWN; the gateway call timed out with no definitive response.
     * The attempt is NOT terminal — it will be resolved by the recovery scheduler.
     */
    PaymentAttempt markUnknown(Long attemptId, Long intentId, Long latencyMs);

    /**
     * Transition to RECONCILED (manual-review state).
     * Called by {@code PaymentOutcomeReconciler} when the gateway status poll also
     * returned no definitive answer and human intervention is required.
     */
    PaymentAttempt markReconciled(Long attemptId, Long intentId);

    /** @return the next sequential attempt number for the given payment intent. */
    int computeNextAttemptNumber(Long paymentIntentId);

    /** Return all attempts for the intent, ordered by attempt_number ASC. */
    List<PaymentAttemptResponseDTO> listByPaymentIntent(Long merchantId, Long intentId);
}
