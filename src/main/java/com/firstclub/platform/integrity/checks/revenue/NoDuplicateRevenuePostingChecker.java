package com.firstclub.platform.integrity.checks.revenue;

import com.firstclub.ledger.revenue.entity.RevenueRecognitionSchedule;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Verifies that no revenue recognition schedule row has been posted more
 * than once (i.e. there must be at most one POSTED row per invoiceId +
 * recognitionDate combination).
 *
 * <p>Duplicate postings inflate income and under-report deferred revenue.
 */
@Component
@RequiredArgsConstructor
public class NoDuplicateRevenuePostingChecker implements IntegrityChecker {

    private static final int LOOK_BACK_DAYS = 180;
    private static final int PREVIEW_CAP = 50;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public String getInvariantKey() {
        return "revenue.no_duplicate_posting";
    }

    @Override
    public IntegrityCheckSeverity getSeverity() {
        return IntegrityCheckSeverity.CRITICAL;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public IntegrityCheckResult run(@Nullable Long merchantId) {
        List<RevenueRecognitionSchedule> postedSchedules = entityManager.createQuery(
                "SELECT s FROM RevenueRecognitionSchedule s WHERE s.status = :status "
                + "AND s.updatedAt >= :since",
                RevenueRecognitionSchedule.class)
                .setParameter("status", RevenueRecognitionStatus.POSTED)
                .setParameter("since", LocalDateTime.now().minusDays(LOOK_BACK_DAYS))
                .setMaxResults(5000)
                .getResultList();

        // Group by (invoiceId, recognitionDate) → count
        Map<String, List<Long>> groups = new HashMap<>();
        for (RevenueRecognitionSchedule s : postedSchedules) {
            if (s.getInvoiceId() == null || s.getRecognitionDate() == null) continue;
            String key = s.getInvoiceId() + ":" + s.getRecognitionDate();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(s.getId());
        }

        List<IntegrityViolation> violations = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            if (entry.getValue().size() > 1) {
                String[] parts = entry.getKey().split(":");
                violations.add(IntegrityViolation.builder()
                        .entityType("REVENUE_RECOGNITION_SCHEDULE")
                        .entityId(Long.parseLong(parts[0]))
                        .details("Duplicate POSTED schedule: " + entry.getValue().size()
                                + " rows for invoiceId=" + parts[0]
                                + ", recognitionDate=" + parts[1]
                                + " (scheduleIds=" + entry.getValue() + ")")
                        .preview("invoiceId=" + parts[0] + ", date=" + parts[1])
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
                        ? "No duplicate revenue postings found in " + postedSchedules.size() + " rows"
                        : violations.size() + " (invoiceId, recognitionDate) pairs have multiple POSTED rows")
                .suggestedRepairKey(passed ? null : "revenue.void_duplicate_recognition_row")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
