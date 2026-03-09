package com.firstclub.payments.refund.service;

import com.firstclub.payments.refund.dto.RefundCreateRequestDTO;
import com.firstclub.payments.refund.dto.RefundV2ResponseDTO;

import java.math.BigDecimal;
import java.util.List;

/**
 * Merchant-scoped refund service (V2).
 *
 * <p>Compared with the legacy {@link com.firstclub.payments.service.RefundService}, this service:
 * <ul>
 *   <li>Validates tenant ownership of the payment before any mutation.</li>
 *   <li>Uses a <em>pessimistic write lock</em> on the payment row to prevent concurrent over-refund.</li>
 *   <li>Tracks cumulative refunds via {@code payments.refunded_amount} and derives
 *       {@code payments.net_amount} after each refund.</li>
 *   <li>Supports partial refunds — payment transitions to {@code PARTIALLY_REFUNDED}
 *       until the full captured amount has been refunded.</li>
 * </ul>
 */
public interface RefundServiceV2 {

    /**
     * Issue a new (partial or full) refund against a payment.
     *
     * @param merchantId path-scoped merchant performing the refund
     * @param paymentId  payment to refund
     * @param request    refund details
     * @return the created refund enriched with the post-refund payment snapshot
     * @throws com.firstclub.membership.exception.MembershipException with code
     *         {@code PAYMENT_NOT_FOUND} (404) if the payment doesn't exist,
     *         {@code PAYMENT_MERCHANT_UNRESOLVED} (422) if the payment has no merchant association,
     *         {@code PAYMENT_MERCHANT_MISMATCH} (403) if the payment belongs to a different merchant,
     *         {@code PAYMENT_NOT_REFUNDABLE} (422) if the payment status is not eligible, or
     *         {@code OVER_REFUND} (422) if the requested amount exceeds the refundable amount.
     */
    RefundV2ResponseDTO createRefund(Long merchantId, Long paymentId, RefundCreateRequestDTO request);

    /**
     * Retrieve a specific refund (scoped to the given merchant).
     *
     * @throws com.firstclub.membership.exception.MembershipException with code {@code REFUND_NOT_FOUND} (404)
     */
    RefundV2ResponseDTO getRefund(Long merchantId, Long refundId);

    /**
     * List all refunds for a given payment (scoped to the given merchant).
     *
     * @throws com.firstclub.membership.exception.MembershipException with code
     *         {@code PAYMENT_NOT_FOUND} (404) if the payment doesn't exist or doesn't belong to the merchant
     */
    List<RefundV2ResponseDTO> listRefundsByPayment(Long merchantId, Long paymentId);

    /**
     * Compute the currently refundable amount for a payment without locking.
     * Use only for read-only queries — the authoritative check happens inside
     * {@link #createRefund} with a pessimistic lock.
     */
    BigDecimal computeRefundableAmount(Long paymentId);
}
