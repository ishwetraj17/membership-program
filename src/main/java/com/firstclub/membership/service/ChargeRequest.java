package com.firstclub.membership.service;

import lombok.Builder;

import java.math.BigDecimal;

/**
 * A request to charge a member, expressed in PSP-agnostic terms. Carries everything a provider
 * adapter needs to make a safe, traceable, idempotent charge — without leaking any provider-specific
 * concept into the membership domain.
 *
 * @param idempotencyKey  Stable, deterministic key for this logical charge. Re-sending the same key
 *                        (on an application retry, a Resilience4j retry, or a client resubmit) MUST
 *                        be deduplicated by the PSP, so a charge can never happen twice. Derived from
 *                        business identity (e.g. {@code charge:renew:<subId>:<periodEnd>}), never random.
 * @param correlationId   Request/trace correlation id for log stitching across services and the PSP.
 * @param customerReference Opaque member reference (our user id as a string) — never PII.
 * @param amount          Amount to charge.
 * @param currency        ISO-4217 currency code (e.g. INR).
 * @param description     Human-readable statement descriptor / memo.
 */
@Builder
public record ChargeRequest(
        String idempotencyKey,
        String correlationId,
        String customerReference,
        BigDecimal amount,
        String currency,
        String description) {
}
