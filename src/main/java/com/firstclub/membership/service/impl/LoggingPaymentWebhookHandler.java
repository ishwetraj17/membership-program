package com.firstclub.membership.service.impl;

import com.firstclub.membership.service.PaymentNotification;
import com.firstclub.membership.service.PaymentWebhookPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Safe default {@link PaymentWebhookPort} — logs and acknowledges the notification without acting on
 * it. It exists so the webhook seam is concrete, wired, and testable today; when a real PSP is
 * integrated, a reconciliation implementation replaces this bean (e.g. confirming a PENDING charge
 * or recording an out-of-band refund) with no change to the inbound contract.
 *
 * <p>This is NOT a PSP integration: it parses nothing, verifies no signature, and exposes no route.
 */
@Component
@Slf4j
public class LoggingPaymentWebhookHandler implements PaymentWebhookPort {

    @Override
    public void onNotification(PaymentNotification notification) {
        log.info("PSP webhook (no-op placeholder) — provider={} eventId={} reference={} status={}",
                notification.provider(), notification.eventId(),
                notification.providerReference(), notification.status());
    }
}
