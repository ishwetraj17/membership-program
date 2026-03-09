package com.firstclub.platform.integrity.checks.payments;

import com.firstclub.payments.entity.PaymentAttemptStatus;
import com.firstclub.payments.entity.PaymentIntentStatusV2;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.repository.PaymentAttemptRepository;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Verifies that terminal payment intents (SUCCEEDED, FAILED, CANCELLED) do
 * not have any attempts still in an in-progress state (STARTED or REQUIRES_ACTION).
 *
 * <p>A STARTED attempt on a terminal intent indicates the orchestrator
 * wrote the intent terminal state without settling the active attempt — a
 * data consistency bug.
 */
@Component
@RequiredArgsConstructor
public class TerminalIntentNoNewAttemptsChecker implements IntegrityChecker {

    private static final Set<PaymentIntentStatusV2> TERMINAL_STATUSES =
            Set.of(PaymentIntentStatusV2.SUCCEEDED, PaymentIntentStatusV2.FAILED,
                   PaymentIntentStatusV2.CANCELLED);
    private static final Set<PaymentAttemptStatus> IN_PROGRESS_ATTEMPT_STATUSES =
            Set.of(PaymentAttemptStatus.STARTED, PaymentAttemptStatus.REQUIRES_ACTION);

    private static final int LOOK_BACK_DAYS = 90;
    private static final int PREVIEW_CAP = 50;

    private final PaymentAttemptRepository attemptRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public String getInvariantKey() {
        return "payments.terminal_intent_no_active_attempts";
    }

    @Override
    public IntegrityCheckSeverity getSeverity() {
        return IntegrityCheckSeverity.HIGH;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public IntegrityCheckResult run(@Nullable Long merchantId) {
        List<PaymentIntentV2> terminalIntents;
        if (merchantId != null) {
            terminalIntents = entityManager.createQuery(
                    "SELECT pi FROM PaymentIntentV2 pi WHERE pi.merchant.id = :mid AND pi.status IN :statuses",
                    PaymentIntentV2.class)
                    .setParameter("mid", merchantId)
                    .setParameter("statuses", TERMINAL_STATUSES)
                    .getResultList();
        } else {
            terminalIntents = entityManager.createQuery(
                    "SELECT pi FROM PaymentIntentV2 pi WHERE pi.status IN :statuses AND pi.createdAt >= :since",
                    PaymentIntentV2.class)
                    .setParameter("statuses", TERMINAL_STATUSES)
                    .setParameter("since", LocalDateTime.now().minusDays(LOOK_BACK_DAYS))
                    .setMaxResults(2000)
                    .getResultList();
        }

        List<IntegrityViolation> violations = new ArrayList<>();

        for (var intent : terminalIntents) {
            var attempts = attemptRepository.findByPaymentIntentIdOrderByAttemptNumberAsc(intent.getId());
            long stuckCount = attempts.stream()
                    .filter(a -> IN_PROGRESS_ATTEMPT_STATUSES.contains(a.getStatus()))
                    .count();

            if (stuckCount > 0) {
                violations.add(IntegrityViolation.builder()
                        .entityType("PAYMENT_INTENT")
                        .entityId(intent.getId())
                        .details("Terminal intent (status=" + intent.getStatus()
                                + ") has " + stuckCount + " in-progress attempt(s)")
                        .preview("intentStatus=" + intent.getStatus()
                                + ", totalAttempts=" + attempts.size())
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
                        ? "All " + terminalIntents.size() + " terminal intents have no in-progress attempts"
                        : violations.size() + " terminal intents have attempts still in STARTED/REQUIRES_ACTION")
                .suggestedRepairKey(passed ? null : "payments.settle_stuck_attempt")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
