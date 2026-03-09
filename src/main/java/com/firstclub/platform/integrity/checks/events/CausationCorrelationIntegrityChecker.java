package com.firstclub.platform.integrity.checks.events;

import com.firstclub.platform.integrity.IntegrityCheckResult;
import com.firstclub.platform.integrity.IntegrityCheckSeverity;
import com.firstclub.platform.integrity.IntegrityChecker;
import com.firstclub.platform.integrity.IntegrityViolation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Validates the integrity of {@code causationId} and {@code correlationId}
 * on domain events.
 *
 * <p>Two invariants are checked:
 * <ol>
 *   <li>When {@code causationId} is set it must be a non-blank string (not {@code ""}).
 *   <li>When {@code correlationId} is set it must be a non-blank string (not {@code ""}).
 * </ol>
 *
 * <p>Empty-string IDs break distributed-trace linking and event-chain reconstruction.
 * Events are append-only so violations indicate a publishing bug.
 */
@Component
@RequiredArgsConstructor
public class CausationCorrelationIntegrityChecker implements IntegrityChecker {

    private static final int LOOK_BACK_DAYS = 7;
    private static final int PREVIEW_CAP = 50;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public String getInvariantKey() {
        return "events.causation_correlation_integrity";
    }

    @Override
    public IntegrityCheckSeverity getSeverity() {
        return IntegrityCheckSeverity.LOW;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public IntegrityCheckResult run(@Nullable Long merchantId) {
        LocalDateTime since = LocalDateTime.now().minusDays(LOOK_BACK_DAYS);

        // Find events where causationId or correlationId is an empty string (not null, not valid)
        String jpql;
        List<Object[]> badEvents;

        if (merchantId != null) {
            jpql = "SELECT e.id, e.eventType, e.causationId, e.correlationId FROM DomainEvent e "
                   + "WHERE e.createdAt >= :since AND e.merchantId = :merchantId "
                   + "AND (e.causationId = '' OR e.correlationId = '')";
            badEvents = entityManager.createQuery(jpql, Object[].class)
                    .setParameter("since", since)
                    .setParameter("merchantId", merchantId)
                    .getResultList();
        } else {
            jpql = "SELECT e.id, e.eventType, e.causationId, e.correlationId FROM DomainEvent e "
                   + "WHERE e.createdAt >= :since "
                   + "AND (e.causationId = '' OR e.correlationId = '')";
            badEvents = entityManager.createQuery(jpql, Object[].class)
                    .setParameter("since", since)
                    .getResultList();
        }

        List<IntegrityViolation> violations = new ArrayList<>();
        for (Object[] row : badEvents) {
            Long eventId       = ((Number) row[0]).longValue();
            String eventType   = (String) row[1];
            String causationId = (String) row[2];
            String correlId    = (String) row[3];

            List<String> problems = new ArrayList<>();
            if ("".equals(causationId))  problems.add("causationId is empty-string");
            if ("".equals(correlId))     problems.add("correlationId is empty-string");

            violations.add(IntegrityViolation.builder()
                    .entityType("DOMAIN_EVENT")
                    .entityId(eventId)
                    .details("Event has invalid tracing identifiers: " + problems)
                    .preview("eventId=" + eventId + ", eventType=" + eventType)
                    .build());
        }

        boolean passed = violations.isEmpty();
        return IntegrityCheckResult.builder()
                .invariantKey(getInvariantKey())
                .severity(getSeverity())
                .passed(passed)
                .violationCount(violations.size())
                .violations(violations.stream().limit(PREVIEW_CAP).collect(Collectors.toList()))
                .details(passed
                        ? "All recent domain events have valid causation/correlation identifiers"
                        : violations.size() + " event(s) have empty-string causationId or correlationId")
                .suggestedRepairKey(null)
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
