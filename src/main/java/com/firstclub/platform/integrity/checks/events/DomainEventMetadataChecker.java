package com.firstclub.platform.integrity.checks.events;

import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.repository.DomainEventRepository;
import com.firstclub.platform.integrity.IntegrityCheckResult;
import com.firstclub.platform.integrity.IntegrityCheckSeverity;
import com.firstclub.platform.integrity.IntegrityChecker;
import com.firstclub.platform.integrity.IntegrityViolation;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Verifies that recent domain events carry the required routing metadata
 * fields: {@code eventType}, {@code aggregateType}, and {@code aggregateId}.
 *
 * <p>Null or blank values in these fields break event replay, audit tooling,
 * and consumer routing.  Events are append-only so violations cannot be
 * repaired — they indicate a publishing bug that must be fixed in the source.
 */
@Component
@RequiredArgsConstructor
public class DomainEventMetadataChecker implements IntegrityChecker {

    private static final int LOOK_BACK_DAYS = 7;
    private static final int PREVIEW_CAP = 50;

    private final DomainEventRepository domainEventRepository;

    @Override
    public String getInvariantKey() {
        return "events.metadata_populated";
    }

    @Override
    public IntegrityCheckSeverity getSeverity() {
        return IntegrityCheckSeverity.MEDIUM;
    }

    @Override
    @Transactional(readOnly = true)
    public IntegrityCheckResult run(@Nullable Long merchantId) {
        LocalDateTime from = LocalDateTime.now().minusDays(LOOK_BACK_DAYS);
        LocalDateTime to   = LocalDateTime.now();

        List<DomainEvent> events;
        if (merchantId != null) {
            events = domainEventRepository
                    .findByMerchantIdAndCreatedAtBetweenOrderByCreatedAtAsc(merchantId, from, to);
        } else {
            events = domainEventRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(from, to);
        }

        List<IntegrityViolation> violations = new ArrayList<>();
        for (DomainEvent event : events) {
            List<String> missing = new ArrayList<>();
            if (isBlank(event.getEventType()))     missing.add("eventType");
            if (isBlank(event.getAggregateType())) missing.add("aggregateType");
            if (isBlank(event.getAggregateId()))   missing.add("aggregateId");

            if (!missing.isEmpty()) {
                violations.add(IntegrityViolation.builder()
                        .entityType("DOMAIN_EVENT")
                        .entityId(event.getId())
                        .details("Domain event is missing required metadata fields: " + missing)
                        .preview("eventId=" + event.getId()
                                + ", eventType=" + event.getEventType()
                                + ", createdAt=" + event.getCreatedAt())
                        .build());
            }
        }

        boolean passed = violations.isEmpty();
        return IntegrityCheckResult.builder()
                .invariantKey(getInvariantKey())
                .severity(getSeverity())
                .passed(passed)
                .violationCount(violations.size())
                .violations(violations.stream().limit(PREVIEW_CAP).collect(Collectors.toList()))
                .details(passed
                        ? "All " + events.size() + " recent domain events have required metadata"
                        : violations.size() + " domain event(s) are missing required metadata fields")
                .suggestedRepairKey(null)
                .checkedAt(LocalDateTime.now())
                .build();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
