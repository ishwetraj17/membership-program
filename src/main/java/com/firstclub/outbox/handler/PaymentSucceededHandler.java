package com.firstclub.outbox.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.outbox.config.DomainEventTypes;
import com.firstclub.outbox.entity.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles {@link DomainEventTypes#PAYMENT_SUCCEEDED} events.
 *
 * <p><b>Idempotency</b>: the handler verifies that the invoice is PAID before
 * acting.  If the invoice is already PAID (event delivered twice), the handler
 * logs and returns without error.  If the invoice is in an unexpected state,
 * it throws so the poller can retry.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentSucceededHandler implements OutboxEventHandler {

    private final InvoiceRepository invoiceRepository;
    private final ObjectMapper      objectMapper;

    @Override
    public String getEventType() {
        return DomainEventTypes.PAYMENT_SUCCEEDED;
    }

    @Override
    @Transactional(readOnly = true)
    public void handle(OutboxEvent event) throws Exception {
        JsonNode json      = objectMapper.readTree(event.getPayload());
        Long     invoiceId = json.get("invoiceId").asLong();

        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalStateException(
                        "PAYMENT_SUCCEEDED handler: invoice " + invoiceId + " not found"));

        // Idempotency: invoice must be PAID (the business change already occurred)
        if (invoice.getStatus() != InvoiceStatus.PAID) {
            throw new IllegalStateException(
                    "PAYMENT_SUCCEEDED handler: invoice " + invoiceId +
                            " expected PAID but was " + invoice.getStatus() +
                            " — transient inconsistency, will retry");
        }

        log.info("[PAYMENT_SUCCEEDED] Invoice {} PAID confirmed — subscriptionId={}, amount={} {}",
                invoiceId, invoice.getSubscriptionId(),
                invoice.getTotalAmount(), invoice.getCurrency());
    }
}
