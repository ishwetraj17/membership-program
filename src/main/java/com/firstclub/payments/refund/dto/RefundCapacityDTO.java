package com.firstclub.payments.refund.dto;

import com.firstclub.payments.entity.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Response DTO for {@code GET /api/v2/admin/payments/{paymentId}/refund-capacity}.
 *
 * <p>Summarises the current refundable headroom so that ops teams can quickly
 * check how much money can still be returned to a customer without fetching and
 * computing from raw payment amounts themselves.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundCapacityDTO {

    private Long paymentId;

    private Long merchantId;

    /** Total amount captured by the gateway. */
    private BigDecimal capturedAmount;

    /** Sum of all COMPLETED refunds. */
    private BigDecimal refundedAmount;

    /** Sum of all open/active disputes. */
    private BigDecimal disputedAmount;

    /**
     * Remaining refundable headroom:
     * {@code capturedAmount - refundedAmount - disputedAmount}.
     */
    private BigDecimal refundableAmount;

    /** Current payment status. */
    private PaymentStatus paymentStatus;
}
