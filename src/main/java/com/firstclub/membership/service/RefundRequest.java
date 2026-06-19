package com.firstclub.membership.service;

import lombok.Builder;

import java.math.BigDecimal;

/**
 * A request to refund a previous charge, in PSP-agnostic terms.
 *
 * @param idempotencyKey   Deterministic key for this refund — derived from the original charge
 *                         reference ({@code refund:<originalReference>}) so a retried compensation
 *                         never refunds twice.
 * @param correlationId    Request/trace correlation id.
 * @param originalReference Provider reference of the charge being refunded.
 * @param amount           Amount to refund (full or pro-rated).
 */
@Builder
public record RefundRequest(
        String idempotencyKey,
        String correlationId,
        String originalReference,
        BigDecimal amount) {
}
