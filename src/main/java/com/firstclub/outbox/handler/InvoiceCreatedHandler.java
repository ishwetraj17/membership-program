package com.firstclub.outbox.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.outbox.config.DomainEventTypes;
import com.firstclub.outbox.entity.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles {@link DomainEventTypes#INVOICE_CREATED} events.
 *
 * <p><b>Idempotency</b>: the invoice must exist and have a non-null status.
 * If it has been deleted or cannot be found, the event handler throws so the
 * poller can retry (and eventually route to DLQ).  Repeated deliveries of the
 * same event are safe — the handler only reads state and logs.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InvoiceCreatedHandler implements OutboxEventHandler {

    private final InvoiceRepository invoiceRepository;
    private final ObjectMapper      objectMapper;

    @Override
    public String getEventType() {
        return DomainEventTypes.INVOICE_CREATED;
    }

    @Override
    @Transactional(readOnly = true)
    public void handle(OutboxEvent event) throws Exception {
        JsonNode json     = objectMapper.readTree(event.getPayload());
        Long     invoiceId = json.get("invoiceId").asLong();

        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalStateException(
                        "INVOICE_CREATED handler: invoice " + invoiceId + " not found"));

        // State check: invoice must be persisted (any non-null status is acceptable)
        if (invoice.getStatus() == null) {
            throw new IllegalStateException(
                    "INVOICE_CREATED handler: invoice " + invoiceId + " has null status");
        }

        log.info("[INVOICE_CREATED] Invoice {} confirmed — userId={}, subscriptionId={}, " +
                        "totalAmount={} {}, status={}",
                invoice.getId(), invoice.getUserId(), invoice.getSubscriptionId(),
                invoice.getTotalAmount(), invoice.getCurrency(), invoice.getStatus());
    }
}
