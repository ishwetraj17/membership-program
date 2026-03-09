package com.firstclub.platform.repair.actions;

import com.firstclub.outbox.entity.OutboxEvent;
import com.firstclub.outbox.repository.OutboxEventRepository;
import com.firstclub.platform.repair.RepairAction;
import com.firstclub.platform.repair.RepairActionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resets a stuck or failed outbox event so the poller will re-process it.
 *
 * <p><b>What changes:</b> {@code status → NEW}, {@code next_attempt_at → now},
 * {@code last_error} cleared on the target {@code outbox_events} row.
 *
 * <p><b>What is never changed:</b> {@code payload}, {@code event_type},
 * {@code attempts} counter (preserved so we have a full history), all
 * immutable identity fields.
 *
 * <p><b>Dry-run:</b> not supported — state reset is trivially reversible by
 * running again.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxEventRetryAction implements RepairAction {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper          objectMapper;

    @Override
    public String getRepairKey() { return "repair.outbox.retry_event"; }

    @Override
    public String getTargetType() { return "OUTBOX_EVENT"; }

    @Override
    public boolean supportsDryRun() { return false; }

    @Override
    @Transactional
    public RepairActionResult execute(RepairContext context) {
        Long eventId = parseId(context.targetId());
        OutboxEvent event = outboxEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("OutboxEvent not found: " + eventId));

        String beforeJson = snapshot(event);

        OutboxEvent.OutboxEventStatus previousStatus = event.getStatus();
        event.setStatus(OutboxEvent.OutboxEventStatus.NEW);
        event.setNextAttemptAt(LocalDateTime.now());
        event.setLastError(null);
        outboxEventRepository.save(event);

        String afterJson = snapshot(event);
        log.info("OutboxEventRetryAction: event={} reset from {} → NEW", eventId, previousStatus);

        return RepairActionResult.builder()
                .repairKey(getRepairKey())
                .success(true)
                .dryRun(false)
                .beforeSnapshotJson(beforeJson)
                .afterSnapshotJson(afterJson)
                .details("OutboxEvent " + eventId + " reset from " + previousStatus + " → NEW for reprocessing")
                .evaluatedAt(LocalDateTime.now())
                .build();
    }

    private Long parseId(String id) {
        try { return Long.parseLong(id); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid outbox event id: " + id); }
    }

    private String snapshot(OutboxEvent ev) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ev.getId());
            m.put("eventType", ev.getEventType());
            m.put("status", ev.getStatus());
            m.put("attempts", ev.getAttempts());
            m.put("nextAttemptAt", ev.getNextAttemptAt());
            m.put("lastError", ev.getLastError());
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) { return "{}"; }
    }
}
