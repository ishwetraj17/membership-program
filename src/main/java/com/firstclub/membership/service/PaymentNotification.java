package com.firstclub.membership.service;

import com.firstclub.membership.service.PaymentGateway.PaymentResult.Status;

/**
 * A normalized, PSP-agnostic inbound payment event — the shape a (future) webhook controller would
 * produce after verifying a provider's signature and mapping its payload. Defined now as an
 * extension point so async confirmations (a {@code PENDING} charge later SUCCEEDED/ FAILED), refunds
 * settled out-of-band, and disputes have a place to land without reshaping the domain later.
 *
 * @param provider          Logical provider name (for routing/audit) — not a coupling to any SDK.
 * @param eventId           Provider's unique event id; the dedupe key for at-least-once webhooks.
 * @param providerReference Charge/refund reference this event concerns (ties back to a PaymentResult).
 * @param status            Normalized terminal status the event reports.
 * @param rawPayload        Original payload, retained for audit/forensics.
 */
public record PaymentNotification(
        String provider,
        String eventId,
        String providerReference,
        Status status,
        String rawPayload) {
}
