package com.firstclub.payments.disputes.service;

import com.firstclub.payments.disputes.dto.DisputeCreateRequestDTO;
import com.firstclub.payments.disputes.dto.DisputeResponseDTO;
import com.firstclub.payments.disputes.dto.DisputeResolveRequestDTO;
import com.firstclub.payments.disputes.entity.DisputeStatus;

import java.util.List;

/**
 * Merchant-scoped dispute lifecycle service.
 *
 * <p>All mutating operations validate that the referenced payment belongs to the
 * calling merchant before making any change ({@code PAYMENT_MERCHANT_MISMATCH → 403}).
 *
 * <p>Only one OPEN or UNDER_REVIEW dispute is allowed per payment at a time
 * ({@code ACTIVE_DISPUTE_EXISTS → 409}).
 */
public interface DisputeService {

    /**
     * Open a dispute against a CAPTURED or PARTIALLY_REFUNDED payment.
     * Moves the payment to DISPUTED and posts DR DISPUTE_RESERVE / CR PG_CLEARING.
     *
     * @throws com.firstclub.membership.exception.MembershipException PAYMENT_NOT_FOUND (404)
     * @throws com.firstclub.membership.exception.MembershipException PAYMENT_MERCHANT_UNRESOLVED (422)
     * @throws com.firstclub.membership.exception.MembershipException PAYMENT_MERCHANT_MISMATCH (403)
     * @throws com.firstclub.membership.exception.MembershipException PAYMENT_NOT_DISPUTABLE (422)
     * @throws com.firstclub.membership.exception.MembershipException ACTIVE_DISPUTE_EXISTS (409)
     * @throws com.firstclub.membership.exception.MembershipException DISPUTE_AMOUNT_EXCEEDS_LIMIT (422)
     */
    DisputeResponseDTO openDispute(Long merchantId, Long paymentId, DisputeCreateRequestDTO request);

    /**
     * Retrieve a single dispute by ID, scoped to the given merchant.
     *
     * @throws com.firstclub.membership.exception.MembershipException DISPUTE_NOT_FOUND (404)
     */
    DisputeResponseDTO getDisputeById(Long merchantId, Long disputeId);

    /**
     * List all disputes for a merchant, optionally filtered by status.
     * Returns an empty list (not 404) when no disputes match.
     */
    List<DisputeResponseDTO> listDisputes(Long merchantId, DisputeStatus status);

    /**
     * List all disputes for a specific payment, scoped to the merchant.
     *
     * @throws com.firstclub.membership.exception.MembershipException PAYMENT_NOT_FOUND (404)
     */
    List<DisputeResponseDTO> listDisputesByPayment(Long merchantId, Long paymentId);

    /**
     * Transition a dispute from OPEN → UNDER_REVIEW.
     *
     * @throws com.firstclub.membership.exception.MembershipException DISPUTE_NOT_FOUND (404)
     * @throws com.firstclub.membership.exception.MembershipException INVALID_DISPUTE_TRANSITION (422)
     */
    DisputeResponseDTO moveToUnderReview(Long merchantId, Long disputeId);

    /**
     * Resolve a dispute as WON or LOST.
     * <ul>
     *   <li>WON  — posts DR PG_CLEARING / CR DISPUTE_RESERVE; restores payment status.</li>
     *   <li>LOST — posts DR CHARGEBACK_EXPENSE / CR DISPUTE_RESERVE; permanently reduces capturedAmount.</li>
     * </ul>
     *
     * @throws com.firstclub.membership.exception.MembershipException DISPUTE_NOT_FOUND (404)
     * @throws com.firstclub.membership.exception.MembershipException DISPUTE_ALREADY_RESOLVED (422)
     * @throws com.firstclub.membership.exception.MembershipException INVALID_DISPUTE_OUTCOME (422)
     */
    DisputeResponseDTO resolveDispute(Long merchantId, Long disputeId, DisputeResolveRequestDTO request);
}
