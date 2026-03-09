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
 * Ensures no two {@code SettlementBatch} rows share the same
 * {@code (merchantId, batchDate)} pair.
 *
 * <p>The table has an index on these columns but not a unique constraint,
 * so duplicate batches could be created by a race condition or idempotency bug
 * in the batch-creation service.
 */
@Component
@RequiredArgsConstructor
public class BatchUniquenessChecker implements IntegrityChecker {

    private static final int PREVIEW_CAP = 50;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public String getInvariantKey() {
        return "recon.batch_uniqueness";
    }

    @Override
    public IntegrityCheckSeverity getSeverity() {
        return IntegrityCheckSeverity.HIGH;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public IntegrityCheckResult run(@Nullable Long merchantId) {
        String jpql;
        List<Object[]> duplicates;

        if (merchantId != null) {
            jpql = "SELECT s.merchantId, s.batchDate, COUNT(s) FROM SettlementBatch s "
                   + "WHERE s.merchantId = :merchantId "
                   + "GROUP BY s.merchantId, s.batchDate HAVING COUNT(s) > 1";
            duplicates = entityManager.createQuery(jpql, Object[].class)
                    .setParameter("merchantId", merchantId)
                    .getResultList();
        } else {
            jpql = "SELECT s.merchantId, s.batchDate, COUNT(s) FROM SettlementBatch s "
                   + "GROUP BY s.merchantId, s.batchDate HAVING COUNT(s) > 1";
            duplicates = entityManager.createQuery(jpql, Object[].class)
                    .getResultList();
        }

        List<IntegrityViolation> violations = new ArrayList<>();
        for (Object[] row : duplicates) {
            violations.add(IntegrityViolation.builder()
                    .entityType("SETTLEMENT_BATCH")
                    .entityId(null)
                    .details("Duplicate settlement batch detected — merchantId=" + row[0]
                            + ", batchDate=" + row[1] + " has " + row[2] + " rows")
                    .preview("merchantId=" + row[0] + ", batchDate=" + row[1])
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
                        ? "All settlement batches have unique (merchantId, batchDate) pairs"
                        : violations.size() + " (merchantId, batchDate) pair(s) have duplicate batches")
                .suggestedRepairKey(passed ? null : "recon.merge_duplicate_batches")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
