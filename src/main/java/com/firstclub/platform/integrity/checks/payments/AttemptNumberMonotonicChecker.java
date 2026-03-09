package com.firstclub.platform.integrity.checks.payments;

import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.repository.PaymentAttemptRepository;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
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
 * Verifies that payment attempt numbers are monotonically increasing
 * starting at 1 with no gaps for each payment intent.
 *
 * <p>Expected sequence: 1, 2, 3, … N. Any gap (e.g. 1, 3) or duplicate
 * indicates a booking bug in the payment orchestrator.
 */
@Component
@RequiredArgsConstructor
public class AttemptNumberMonotonicChecker implements IntegrityChecker {

    private static final int LOOK_BACK_DAYS = 90;
    private static final int PREVIEW_CAP = 50;
    private static final int MAX_INTENTS_CHECKED = 2000;

    private final PaymentAttemptRepository attemptRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public String getInvariantKey() {
        return "payments.attempt_number_monotonic";
    }

    @Override
    public IntegrityCheckSeverity getSeverity() {
        return IntegrityCheckSeverity.HIGH;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public IntegrityCheckResult run(@Nullable Long merchantId) {
        List<Long> intentIds;
        if (merchantId != null) {
            intentIds = entityManager
                    .createQuery("SELECT pi.id FROM PaymentIntentV2 pi WHERE pi.merchant.id = :mid", Long.class)
                    .setParameter("mid", merchantId)
                    .getResultList();
        } else {
            intentIds = entityManager
                    .createQuery("SELECT pi.id FROM PaymentIntentV2 pi WHERE pi.createdAt >= :since", Long.class)
                    .setParameter("since", LocalDateTime.now().minusDays(LOOK_BACK_DAYS))
                    .setMaxResults(MAX_INTENTS_CHECKED)
                    .getResultList();
        }

        List<IntegrityViolation> violations = new ArrayList<>();

        for (Long intentId : intentIds) {
            List<PaymentAttempt> attempts =
                    attemptRepository.findByPaymentIntentIdOrderByAttemptNumberAsc(intentId);
            if (attempts.isEmpty()) continue;

            for (int i = 0; i < attempts.size(); i++) {
                int expected = i + 1;
                int actual = attempts.get(i).getAttemptNumber();
                if (actual != expected) {
                    violations.add(IntegrityViolation.builder()
                            .entityType("PAYMENT_INTENT")
                            .entityId(intentId)
                            .details("Attempt sequence broken: expected attemptNumber=" + expected
                                    + " but found " + actual
                                    + " at position " + (i + 1))
                            .preview("intentId=" + intentId
                                    + ", totalAttempts=" + attempts.size())
                            .build());
                    break;  // report only first gap per intent
                }
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
                        ? "All " + intentIds.size() + " payment intents have monotonic attempt sequences"
                        : violations.size() + " payment intents have non-monotonic attempt numbers")
                .suggestedRepairKey(passed ? null : "payments.renumber_attempts")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
