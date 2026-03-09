package com.firstclub.platform.ops.service.impl;

import com.firstclub.outbox.entity.OutboxEvent;
import com.firstclub.outbox.entity.OutboxEvent.OutboxEventStatus;
import com.firstclub.outbox.repository.OutboxEventRepository;
import com.firstclub.payments.entity.DeadLetterMessage;
import com.firstclub.payments.repository.DeadLetterMessageRepository;
import com.firstclub.platform.ops.dto.DlqEntryResponseDTO;
import com.firstclub.platform.ops.dto.DlqSummaryDTO;
import com.firstclub.platform.ops.service.DlqOpsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DlqOpsServiceImpl implements DlqOpsService {

    private final DeadLetterMessageRepository deadLetterMessageRepository;
    private final OutboxEventRepository       outboxEventRepository;

    @Override
    @Transactional(readOnly = true)
    public List<DlqEntryResponseDTO> listDlqEntries(String source, String failureCategory) {
        List<DeadLetterMessage> messages;

        if (source != null && failureCategory != null) {
            messages = deadLetterMessageRepository.findBySourceAndFailureCategory(source, failureCategory);
        } else if (source != null) {
            messages = deadLetterMessageRepository.findBySource(source);
        } else if (failureCategory != null) {
            messages = deadLetterMessageRepository.findByFailureCategory(failureCategory);
        } else {
            messages = deadLetterMessageRepository.findAll();
        }

        return messages.stream().map(DlqEntryResponseDTO::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DlqSummaryDTO getDlqSummary() {
        long totalCount = deadLetterMessageRepository.count();

        Map<String, Long> bySource = new LinkedHashMap<>();
        for (Object[] row : deadLetterMessageRepository.countGroupedBySource()) {
            bySource.put((String) row[0], (Long) row[1]);
        }

        Map<String, Long> byFailureCategory = new LinkedHashMap<>();
        for (Object[] row : deadLetterMessageRepository.countGroupedByFailureCategory()) {
            String key = row[0] != null ? (String) row[0] : "UNCATEGORIZED";
            byFailureCategory.put(key, (Long) row[1]);
        }

        return new DlqSummaryDTO(totalCount, bySource, byFailureCategory, LocalDateTime.now());
    }

    /**
     * Re-queues the DLQ entry as a fresh outbox event and deletes the DLQ record.
     *
     * <p><b>Phase 16 fix:</b> The DLQ payload is stored as
     * {@code {eventType}|{jsonPayload}} (written by
     * {@code OutboxService.writeToDLQ()}).  This method parses the pipe delimiter
     * so the new outbox event gets the real {@code eventType} (e.g.
     * {@code "INVOICE_CREATED"}), not the literal string {@code "OUTBOX"} which
     * was the pre-Phase-16 bug.
     */
    @Override
    @Transactional
    public DlqEntryResponseDTO retryDlqEntry(Long id) {
        DeadLetterMessage dlq = deadLetterMessageRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "DLQ entry not found: " + id));

        // Parse {eventType}|{jsonPayload} written by OutboxService.writeToDLQ()
        String rawPayload = dlq.getPayload();
        String eventType;
        String eventPayload;
        if (rawPayload != null && rawPayload.contains("|")) {
            String[] parts = rawPayload.split("\\|", 2);
            eventType    = parts[0];
            eventPayload = parts[1];
        } else {
            // Defensive fallback for legacy DLQ records without pipe delimiter
            eventType    = dlq.getSource();
            eventPayload = rawPayload;
        }

        OutboxEvent requeued = OutboxEvent.builder()
                .eventType(eventType)
                .payload(eventPayload)
                .status(OutboxEventStatus.NEW)
                .nextAttemptAt(LocalDateTime.now())
                .attempts(0)
                .build();
        outboxEventRepository.save(requeued);

        log.info("DLQ entry {} re-queued as outbox event (eventType={}, source={})",
                id, eventType, dlq.getSource());

        DlqEntryResponseDTO dto = DlqEntryResponseDTO.from(dlq);
        deadLetterMessageRepository.delete(dlq);
        return dto;
    }
}

