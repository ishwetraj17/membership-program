package com.firstclub.dunning;

import com.firstclub.dunning.classification.FailureCategory;
import com.firstclub.dunning.repository.DunningAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the intelligence-layer decision fields onto a {@code DunningAttempt} row.
 *
 * <p>Called after the strategy engine has produced a {@link DunningDecision} so that
 * every attempt that flows through the v2 engine has a full, auditable record of:
 * which failure category was inferred, what decision was taken, why, and whether
 * the dunning run was stopped before exhausting the queue.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DunningDecisionAuditService {

    private final DunningAttemptRepository dunningAttemptRepository;

    /**
     * Stamp the intelligence fields on the attempt identified by {@code attemptId}.
     *
     * <p>This method is a no-op when the attempt no longer exists (e.g. test fixtures
     * that do not persist to a real database).
     *
     * @param attemptId  DB id of the dunning attempt to update
     * @param decision   decision taken by the strategy engine
     * @param reason     human-readable reason string (stored as {@code decision_reason})
     * @param category   classified failure category (may be {@code null} for a success path)
     * @param stoppedEarly {@code true} when a non-retryable code caused early termination
     */
    @Transactional
    public void record(Long attemptId, DunningDecision decision, String reason,
                       FailureCategory category, boolean stoppedEarly) {
        dunningAttemptRepository.findById(attemptId).ifPresentOrElse(attempt -> {
            attempt.setFailureCategory(category != null ? category.name() : null);
            attempt.setDecisionTaken(decision.name());
            attempt.setDecisionReason(reason);
            attempt.setStoppedEarly(stoppedEarly);
            dunningAttemptRepository.save(attempt);
            log.debug("Dunning audit: attempt={} decision={} category={} stoppedEarly={}",
                    attemptId, decision, category, stoppedEarly);
        }, () -> log.warn("DunningDecisionAuditService.record: attempt {} not found — skipped",
                attemptId));
    }
}
