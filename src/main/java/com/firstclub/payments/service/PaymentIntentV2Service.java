package com.firstclub.payments.service;

import com.firstclub.payments.dto.PaymentAttemptResponseDTO;
import com.firstclub.payments.dto.PaymentIntentConfirmRequestDTO;
import com.firstclub.payments.dto.PaymentIntentCreateRequestDTO;
import com.firstclub.payments.dto.PaymentIntentV2ResponseDTO;

import java.util.List;

/**
 * Service governing the creation, confirmation, and cancellation of
 * {@code payment_intents_v2} records.
 *
 * <h3>State machine</h3>
 * <pre>
 * REQUIRES_PAYMENT_METHOD ──(attach PM)──► REQUIRES_CONFIRMATION
 *         │                                         │
 *         └──────────(confirm with PM)──────────────┘
 *                                                   ▼
 *                                               PROCESSING
 *                                              /    |     \
 *                                      SUCCEEDED  FAILED  REQUIRES_ACTION
 * </pre>
 *
 * <h3>Idempotency</h3>
 * {@code createPaymentIntent} stores the caller-supplied {@code Idempotency-Key};
 * a subsequent call with the same key returns the original resource verbatim.
 * {@code confirmPaymentIntent} returns the current snapshot when the intent is
 * already in a terminal success state, preventing accidental double-charges.
 */
public interface PaymentIntentV2Service {

    /**
     * Create a payment intent, or return an existing one when the
     * {@code idempotencyKey} matches a previous request for the same merchant.
     */
    PaymentIntentV2ResponseDTO createPaymentIntent(Long merchantId,
                                                    String idempotencyKey,
                                                    PaymentIntentCreateRequestDTO request);

    /** Fetch a payment intent that belongs to the given merchant. */
    PaymentIntentV2ResponseDTO getPaymentIntent(Long merchantId, Long id);

    /**
     * Confirm a payment intent: attach a payment method if needed, spawn a new
     * gateway attempt, and drive the intent to SUCCEEDED or FAILED.
     *
     * <p>Idempotent: if the intent is already SUCCEEDED the method returns
     * its current snapshot without spawning another attempt.</p>
     */
    PaymentIntentV2ResponseDTO confirmPaymentIntent(Long merchantId, Long id,
                                                     PaymentIntentConfirmRequestDTO request);

    /** Cancel the intent if it is still in a cancellable state. */
    PaymentIntentV2ResponseDTO cancelPaymentIntent(Long merchantId, Long id);

    /**
     * Trigger async reconciliation of any UNKNOWN attempts on the intent.
     *
     * <p>Queries the gateway for the outcome of each UNKNOWN attempt and resolves
     * them to SUCCEEDED, FAILED, or RECONCILED. Returns the updated intent snapshot.</p>
     */
    PaymentIntentV2ResponseDTO reconcileGatewayStatus(Long merchantId, Long id);

    /** Return all attempts for the intent, ordered by attempt_number ASC. */
    List<PaymentAttemptResponseDTO> listAttempts(Long merchantId, Long id);
}
