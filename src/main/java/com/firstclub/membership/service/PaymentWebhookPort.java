package com.firstclub.membership.service;

/**
 * Inbound extension point for future PSP webhooks. A signature-verifying webhook controller (added
 * when a real PSP is integrated) would parse and authenticate the provider payload, normalize it
 * into a {@link PaymentNotification}, and hand it to this port — which owns reconciliation
 * (confirm/settle a PENDING charge, record an out-of-band refund or dispute).
 *
 * <p>Deliberately just a seam: no controller, no route, and no provider code exist yet. Keeping the
 * port here means the reconciliation contract is fixed and testable in advance, and the eventual
 * controller is a thin adapter rather than a redesign.
 *
 * <p>Implementations MUST be idempotent on {@link PaymentNotification#eventId()} — webhooks are
 * at-least-once.
 */
public interface PaymentWebhookPort {

    void onNotification(PaymentNotification notification);
}
