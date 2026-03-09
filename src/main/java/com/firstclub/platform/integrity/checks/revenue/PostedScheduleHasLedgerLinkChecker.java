package com.firstclub.platform.integrity.checks.revenue;

import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerReferenceType;
import com.firstclub.ledger.repository.LedgerEntryRepository;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Verifies that every POSTED revenue recognition schedule row has a
 * non-null {@code ledgerEntryId} and that the referenced ledger entry
 * actually exists in {@code ledger_entries}.
 *
 * <p>A POSTED row without a ledger entry link means the income account was
 * not updated — an accounting completeness violation.
 */
@Component
@RequiredArgsConstructor
public class PostedScheduleHasLedgerLinkChecker implements IntegrityChecker {

    private static final int LOOK_BACK_DAYS = 180;
    private static final int PREVIEW_CAP = 50;

    private final LedgerEntryRepository ledgerEntryRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public String getInvariantKey() {
        return "revenue.posted_schedule_has_ledger_link";
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

        List<IntegrityViolation> violations = new ArrayList<>();

        for (RevenueRecognitionSchedule schedule : postedSchedules) {
            // Must have a ledgerEntryId
            if (schedule.getLedgerEntryId() == null) {
                violations.add(IntegrityViolation.builder()
                        .entityType("REVENUE_RECOGNITION_SCHEDULE")
                        .entityId(schedule.getId())
                        .details("POSTED schedule has null ledgerEntryId — revenue posted without ledger link")
                        .preview("invoiceId=" + schedule.getInvoiceId()
                                + ", recognitionDate=" + schedule.getRecognitionDate()
                                + ", amount=" + schedule.getAmount())
                        .build());
                continue;
            }

            // Verify the referenced ledger entry exists
            var entries = ledgerEntryRepository
                    .findByReferenceTypeAndReferenceId(
                            LedgerReferenceType.REVENUE_RECOGNITION_SCHEDULE, schedule.getId());
            boolean hasEntry = entries.stream()
                    .anyMatch(e -> LedgerEntryType.REVENUE_RECOGNIZED.equals(e.getEntryType()));

            if (!hasEntry) {
                violations.add(IntegrityViolation.builder()
                        .entityType("REVENUE_RECOGNITION_SCHEDULE")
                        .entityId(schedule.getId())
                        .details("POSTED schedule references ledgerEntryId=" + schedule.getLedgerEntryId()
                                + " but no matching REVENUE_RECOGNIZED entry exists in ledger_entries")
                        .preview("invoiceId=" + schedule.getInvoiceId()
                                + ", amount=" + schedule.getAmount())
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
                        ? "All " + postedSchedules.size() + " POSTED schedules have valid ledger links"
                        : violations.size() + " POSTED schedules are missing or have broken ledger links")
                .suggestedRepairKey(passed ? null : "revenue.post_missing_ledger_entry_for_schedule")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
