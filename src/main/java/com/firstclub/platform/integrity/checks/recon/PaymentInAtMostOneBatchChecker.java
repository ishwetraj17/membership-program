package com.firstclub.platform.integrity.checks.recon;

import com.firstclub.platform.integrity.IntegrityCheckResult;
import com.firstclub.platform.integrity.IntegrityCheckSeverity;
import com.firstclub.platform.integrity.IntegrityChecker;
import com.firstclub.platform.integrity.IntegrityViolation;
import com.firstclub.recon.entity.SettlementBatchStatus;
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
 * Ensures each payment appears in at most one POSTED {@code SettlementBatch}.
 *
 * <p>A payment included in two POSTED settlement batches means it would be
 * counted twice in the reconciliation ledger — a direct financial integrity
 * violation.  Only POSTED batches are checked; CREATED/CANCELLED batches are
 * work-in-progress and may legitimately reference the same payment during retry.
 */
@Component
@RequiredArgsConstructor
public class PaymentInAtMostOneBatchChecker implements IntegrityChecker {

    private static final int PREVIEW_CAP = 50;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public String getInvariantKey() {
        return "recon.payment_in_at_most_one_batch";
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
            jpql = "SELECT i.paymentId, COUNT(DISTINCT b.id) FROM SettlementBatchItem i "
                   + "JOIN SettlementBatch b ON b.id = i.batchId "
                   + "WHERE b.status = :status AND b.merchantId = :merchantId "
                   + "GROUP BY i.paymentId HAVING COUNT(DISTINCT b.id) > 1";
            duplicates = entityManager.createQuery(jpql, Object[].class)
                    .setParameter("status", SettlementBatchStatus.POSTED)
                    .setParameter("merchantId", merchantId)
                    .getResultList();
        } else {
            jpql = "SELECT i.paymentId, COUNT(DISTINCT b.id) FROM SettlementBatchItem i "
                   + "JOIN SettlementBatch b ON b.id = i.batchId "
                   + "WHERE b.status = :status "
                   + "GROUP BY i.paymentId HAVING COUNT(DISTINCT b.id) > 1";
            duplicates = entityManager.createQuery(jpql, Object[].class)
                    .setParameter("status", SettlementBatchStatus.POSTED)
                    .getResultList();
        }

        List<IntegrityViolation> violations = new ArrayList<>();
        for (Object[] row : duplicates) {
            Long paymentId = ((Number) row[0]).longValue();
            violations.add(IntegrityViolation.builder()
                    .entityType("SETTLEMENT_BATCH_ITEM")
                    .entityId(paymentId)
                    .details("paymentId=" + paymentId + " appears in " + row[1]
                            + " POSTED settlement batches (maximum allowed: 1)")
                    .preview("paymentId=" + paymentId + ", batchCount=" + row[1])
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
                        ? "All payments appear in at most one POSTED settlement batch"
                        : violations.size() + " payment(s) appear in multiple POSTED settlement batches")
                .suggestedRepairKey(passed ? null : "recon.remove_duplicate_batch_item")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
