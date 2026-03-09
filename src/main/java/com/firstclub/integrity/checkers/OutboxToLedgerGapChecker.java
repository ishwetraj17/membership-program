package com.firstclub.integrity.checkers;

import com.firstclub.integrity.InvariantChecker;
import com.firstclub.integrity.InvariantResult;
import com.firstclub.integrity.InvariantSeverity;
import com.firstclub.integrity.InvariantViolation;
import com.firstclub.outbox.entity.OutboxEvent;
import com.firstclub.outbox.entity.OutboxEvent.OutboxEventStatus;
import com.firstclub.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects outbox events that permanently failed to publish, which may indicate
 * a gap where the corresponding ledger entry was never posted downstream.
 *
 * <p>Each {@code FAILED} outbox event represents a domain event (payment captured,
 * refund processed, etc.) that was never delivered to consumers.  If the consumer
 * is responsible for posting ledger entries, those entries are missing.
 */
@Component
@RequiredArgsConstructor
public class OutboxToLedgerGapChecker implements InvariantChecker {

    public static final String NAME = "OUTBOX_TO_LEDGER_GAP";
    private static final String REPAIR =
            "For each FAILED outbox event: inspect the event payload and determine whether the "
            + "downstream ledger-posting consumer received the event via a different path. If not, "
            + "re-publish the event via OutboxService.retry(eventId) and confirm the ledger entry "
            + "is created. Reset the event status to NEW after investigation.";

    private final OutboxEventRepository outboxEventRepository;

    @Override public String getName()               { return NAME; }
    @Override public InvariantSeverity getSeverity() { return InvariantSeverity.MEDIUM; }

    @Override
    public InvariantResult check() {
        List<OutboxEvent> failedEvents = outboxEventRepository.findByStatus(OutboxEventStatus.FAILED);

        if (failedEvents.isEmpty()) {
            return InvariantResult.pass(NAME, getSeverity());
        }

        List<InvariantViolation> violations = new ArrayList<>();
        for (OutboxEvent event : failedEvents) {
            violations.add(InvariantViolation.builder()
                    .entityType("OutboxEvent")
                    .entityId(String.valueOf(event.getId()))
                    .description(String.format(
                            "OutboxEvent %d (type=%s, aggregateId=%s) is in FAILED state — "
                            + "downstream ledger posting may not have occurred",
                            event.getId(), event.getEventType(), event.getAggregateId()))
                    .suggestedRepairAction(REPAIR)
                    .build());
        }

        return InvariantResult.fail(NAME, getSeverity(), violations);
    }
}
