package com.firstclub.payments.refund.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request body for issuing a new refund via
 * {@code POST /api/v2/merchants/{merchantId}/payments/{paymentId}/refunds}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundCreateRequestDTO {

    /**
     * Amount to refund. Must be positive and must not exceed the remaining
     * refundable amount on the payment ({@code capturedAmount - refundedAmount - disputedAmount}).
     */
    @NotNull
    @Positive
    private BigDecimal amount;

    /**
     * Short business reason code for the refund (e.g. {@code "CUSTOMER_REQUEST"},
     * {@code "DUPLICATE_CHARGE"}, {@code "SERVICE_NOT_DELIVERED"}).
     */
    @NotBlank
    @Size(max = 64)
    private String reasonCode;

    /** Optional invoice that this refund is applied against. */
    private Long invoiceId;

    /** Optional gateway or internal correlation reference for reconciliation. */
    @Size(max = 128)
    private String refundReference;

    /**
     * Optional caller-supplied idempotency fingerprint (max 255 chars).
     * When absent the service auto-generates SHA-256(merchantId:paymentId:amount:reasonCode).
     * A second request with the same fingerprint returns the existing refund instead of
     * creating a duplicate.
     */
    @Size(max = 255)
    private String requestFingerprint;
}
