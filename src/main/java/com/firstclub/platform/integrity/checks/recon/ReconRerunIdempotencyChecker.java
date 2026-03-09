package com.firstclub.platform.integrity.checks.recon;

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
 * Ensures no two {@code ReconReport} rows share the same {@code reportDate}.
 *
 * <p>A {@code UNIQUE} constraint guards this at the DB level, but it can be
 * bypassed via low-level inserts, schema changes, or migration scripts.  This
 * checker verifies the invariant from the application layer so violations are
 * surfaced in the integrity dashboard even if the constraint is later removed.
 */
@Component
@RequiredArgsConstructor
public class ReconRerunIdempotencyChecker implements IntegrityChecker {

    private static final int PREVIEW_CAP = 50;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public String getInvariantKey() {
        return "recon.rerun_idempotency";
    }

    @Override
    public IntegrityCheckSeverity getSeverity() {
        return IntegrityCheckSeverity.MEDIUM;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public IntegrityCheckResult run(@Nullable Long merchantId) {
        // Each reportDate must be unique; find any duplicates
        List<Object[]> duplicates = entityManager.createQuery(
                "SELECT r.reportDate, COUNT(r) FROM ReconReport r "
                + "GROUP BY r.reportDate HAVING COUNT(r) > 1",
                Object[].class)
                .getResultList();

        List<IntegrityViolation> violations = new ArrayList<>();
        for (Object[] row : duplicates) {
            violations.add(IntegrityViolation.builder()
                    .entityType("RECON_REPORT")
                    .entityId(null)
                    .details("Duplicate recon report found for reportDate=" + row[0]
                            + " (" + row[1] + " rows)")
                    .preview("reportDate=" + row[0])
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
                        ? "All recon report dates are unique"
                        : violations.size() + " report date(s) have duplicate entries")
                .suggestedRepairKey(passed ? null : "recon.remove_duplicate_report")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
