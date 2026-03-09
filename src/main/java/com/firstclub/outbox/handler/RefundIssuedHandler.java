package com.firstclub.outbox.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.outbox.config.DomainEventTypes;
import com.firstclub.outbox.entity.OutboxEvent;
import com.firstclub.payments.entity.Refund;
import com.firstclub.payments.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles {@link DomainEventTypes#REFUND_ISSUED} events.
 *
 * <p><b>Idempotency</b>: the handler verifies that the refund record exists.
 * Repeated deliveries only log; no state is mutated.  This handler is the
 * natural place to trigger customer notification emails or sync refund data
 * to an external system.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RefundIssuedHandler implements OutboxEventHandler {

    private final RefundRepository refundRepository;
    private final ObjectMapper     objectMapper;

    @Override
    public String getEventType() {
        return DomainEventTypes.REFUND_ISSUED;
    }

    @Override
    @Transactional(readOnly = true)
    public void handle(OutboxEvent event) throws Exception {
        JsonNode json     = objectMapper.readTree(event.getPayload());
        Long     refundId = json.get("refundId").asLong();

        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new IllegalStateException(
                        "REFUND_ISSUED handler: refund " + refundId + " not found"));

        log.info("[REFUND_ISSUED] Refund {} confirmed — paymentId={}, amount={} {}, status={}",
                refund.getId(), refund.getPaymentId(),
                refund.getAmount(), refund.getCurrency(),
                refund.getStatus());
    }
}
