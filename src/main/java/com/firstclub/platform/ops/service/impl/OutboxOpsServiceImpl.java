package com.firstclub.platform.ops.service.impl;

import com.firstclub.outbox.entity.OutboxEvent.OutboxEventStatus;
import com.firstclub.outbox.repository.OutboxEventRepository;
import com.firstclub.outbox.service.OutboxService;
import com.firstclub.platform.ops.dto.OutboxLagResponseDTO;
import com.firstclub.platform.ops.service.OutboxOpsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OutboxOpsServiceImpl implements OutboxOpsService {

    private final OutboxEventRepository outboxEventRepository;

    @Override
    @Transactional(readOnly = true)
    public OutboxLagResponseDTO getOutboxLag() {
        LocalDateTime now = LocalDateTime.now();

        long newCount        = outboxEventRepository.countByStatus(OutboxEventStatus.NEW);
        long processingCount = outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSING);
        long failedCount     = outboxEventRepository.countByStatus(OutboxEventStatus.FAILED);
        long doneCount       = outboxEventRepository.countByStatus(OutboxEventStatus.DONE);

        List<Object[]> rows = outboxEventRepository.countActiveByEventType(OutboxEventStatus.DONE);
        Map<String, Long> byEventType = new LinkedHashMap<>();
        for (Object[] row : rows) {
            byEventType.put((String) row[0], (Long) row[1]);
        }

        // Stale lease count: PROCESSING events stuck longer than the recovery threshold
        LocalDateTime staleThreshold = now.minusMinutes(OutboxService.STALE_LEASE_MINUTES);
        long staleLeasesCount = outboxEventRepository
                .findStaleProcessing(OutboxEventStatus.PROCESSING, staleThreshold)
                .size();

        // Age of the oldest pending event (NEW or PROCESSING)
        Optional<LocalDateTime> oldest = outboxEventRepository.findOldestCreatedAtInStatuses(
                List.of(OutboxEventStatus.NEW, OutboxEventStatus.PROCESSING));
        Long oldestPendingAgeSeconds = oldest
                .map(t -> Duration.between(t, now).getSeconds())
                .orElse(null);

        return new OutboxLagResponseDTO(
                newCount,
                processingCount,
                failedCount,
                doneCount,
                newCount + processingCount + failedCount,
                byEventType,
                staleLeasesCount,
                oldestPendingAgeSeconds,
                now);
    }
}
