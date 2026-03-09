package com.firstclub.platform.integrity.checks.ledger;

import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerReferenceType;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Verifies that no payment has more than one
 * {@link LedgerEntryType#PAYMENT_CAPTURED} journal entry.
 *
 * <p>A duplicate capture entry represents a double-counted revenue event,
 * which is a critical accounting integrity violation.
 */
@Component
@RequiredArgsConstructor
public class NoDuplicateJournalChecker implements IntegrityChecker {

    private static final int LOOK_BACK_DAYS = 90;
    private static final int PREVIEW_CAP = 50;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public String getInvariantKey() {
        return "ledger.no_duplicate_journal_entry";
    }

    @Override
    public IntegrityCheckSeverity getSeverity() {
        return IntegrityCheckSeverity.CRITICAL;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public IntegrityCheckResult run(@Nullable Long merchantId) {
        // Find all PAYMENT_CAPTURED entries in the window
        List<LedgerEntry> captureEntries = entityManager.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.entryType = :type AND e.createdAt >= :since",
                LedgerEntry.class)
                .setParameter("type", LedgerEntryType.PAYMENT_CAPTURED)
                .setParameter("since", LocalDateTime.now().minusDays(LOOK_BACK_DAYS))
                .setMaxResults(5000)
                .getResultList();

        // Group by (referenceType, referenceId) to find duplicates
        Map<String, List<Long>> groupedByRef = new HashMap<>();
        for (LedgerEntry entry : captureEntries) {
            String key = entry.getReferenceType() + ":" + entry.getReferenceId();
            groupedByRef.computeIfAbsent(key, k -> new ArrayList<>()).add(entry.getId());
        }

        List<IntegrityViolation> violations = new ArrayList<>();
        for (var entry : groupedByRef.entrySet()) {
            if (entry.getValue().size() > 1) {
                String[] parts = entry.getKey().split(":");
                violations.add(IntegrityViolation.builder()
                        .entityType("LEDGER_ENTRY_GROUP")
                        .entityId(Long.parseLong(parts[1]))
                        .details("Duplicate PAYMENT_CAPTURED entries: " + entry.getValue().size()
                                + " entries found for " + entry.getKey()
                                + " (entryIds=" + entry.getValue() + ")")
                        .preview("referenceType=" + parts[0] + ", referenceId=" + parts[1])
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
                        ? "No duplicate PAYMENT_CAPTURED journal entries found"
                        : violations.size() + " payment references have multiple PAYMENT_CAPTURED entries")
                .suggestedRepairKey(passed ? null : "ledger.post_reversing_correcting_entry")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
