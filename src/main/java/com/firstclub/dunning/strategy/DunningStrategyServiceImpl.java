package com.firstclub.dunning.strategy;

import com.firstclub.dunning.DunningDecision;
import com.firstclub.dunning.classification.FailureCategory;
import com.firstclub.dunning.entity.DunningAttempt;
import com.firstclub.dunning.entity.DunningAttempt.DunningStatus;
import com.firstclub.dunning.entity.DunningPolicy;
import com.firstclub.dunning.repository.DunningAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Default implementation of {@link DunningStrategyService}.
 *
 * <p>Decision rules in priority order:
 * <ol>
 *   <li>If the failure category is <b>non-retryable</b> → {@link DunningDecision#STOP}.
 *       Continuing would breach compliance or simply waste attempts.</li>
 *   <li>If the failure category calls for a <b>backup method</b>:
 *       <ul>
 *         <li>backup-eligible (policy allows fallback, not already on backup, backup exists)
 *             → {@link DunningDecision#RETRY_WITH_BACKUP}</li>
 *         <li>otherwise → {@link DunningDecision#STOP} (instrument is broken, no fallback)</li>
 *       </ul></li>
 *   <li>If all remaining scheduled attempts are exhausted → {@link DunningDecision#EXHAUSTED}.</li>
 *   <li>Default → {@link DunningDecision#RETRY} (let the queue proceed naturally).</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class DunningStrategyServiceImpl implements DunningStrategyService {

    /** Categories that must never be retried regardless of remaining attempts. */
    private static final Set<FailureCategory> NON_RETRYABLE = Set.of(
            FailureCategory.CARD_STOLEN,
            FailureCategory.CARD_LOST,
            FailureCategory.FRAUDULENT,
            FailureCategory.DO_NOT_HONOR,
            FailureCategory.INVALID_ACCOUNT
    );

    /** Categories where only a different payment instrument can cure the failure. */
    private static final Set<FailureCategory> NEEDS_BACKUP = Set.of(
            FailureCategory.CARD_EXPIRED,
            FailureCategory.CARD_NOT_SUPPORTED,
            FailureCategory.ISSUER_NOT_AVAILABLE
    );

    private final DunningAttemptRepository     dunningAttemptRepository;
    private final BackupPaymentMethodSelector  backupSelector;

    @Override
    public DunningDecision decide(DunningAttempt attempt, FailureCategory category,
                                  DunningPolicy policy) {

        // Rule 1 — non-retryable: stop the entire dunning run immediately
        if (NON_RETRYABLE.contains(category)) {
            return DunningDecision.STOP;
        }

        // Rule 2 — needs backup: escalate to a different instrument if possible
        if (NEEDS_BACKUP.contains(category)) {
            boolean backupEligible = policy.isFallbackToBackupPaymentMethod()
                    && !attempt.isUsedBackupMethod()
                    && backupSelector.findBackup(attempt.getSubscriptionId()).isPresent();
            return backupEligible ? DunningDecision.RETRY_WITH_BACKUP : DunningDecision.STOP;
        }

        // Rule 3 — check remaining scheduled attempts
        long remaining = dunningAttemptRepository
                .countBySubscriptionIdAndDunningPolicyIdIsNotNullAndStatus(
                        attempt.getSubscriptionId(), DunningStatus.SCHEDULED);
        if (remaining == 0) {
            return DunningDecision.EXHAUSTED;
        }

        // Rule 4 — ordinary retryable failure; queue will handle next attempt
        return DunningDecision.RETRY;
    }
}
