package com.firstclub.ledger.revenue.service.impl;

import com.firstclub.ledger.revenue.entity.RevenueRecognitionSchedule;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus;
import com.firstclub.ledger.revenue.guard.GuardDecision;
import com.firstclub.ledger.revenue.guard.RecognitionPolicyCode;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal transaction helper for {@link RevenueCatchUpServiceImpl}.
 *
 * <p>Each method runs in its own {@code REQUIRES_NEW} transaction so that the
 * guard-decision stamp and (optionally) the SKIPPED status are committed to the
 * database <em>before</em> the calling code invokes the posting path in another
 * {@code REQUIRES_NEW} transaction.  This prevents the guard fields written here
 * from being overwritten by the posting service's {@code findByIdWithLock} +
 * save cycle (the posting service only sets {@code status}, {@code ledgerEntryId},
 * {@code postingRunId}, and the Phase 15 {@code recognizedAmountMinor}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RevenueCatchUpTransactionHelper {

    private final RevenueRecognitionScheduleRepository scheduleRepository;

    /**
     * Stamps guard decision on the schedule, marks it {@code SKIPPED}, and commits.
     * Called for {@link GuardDecision#BLOCK} and {@link GuardDecision#HALT}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stampAndSkip(Long scheduleId,
                             GuardDecision decision,
                             RecognitionPolicyCode policyCode,
                             String reason) {
        scheduleRepository.findByIdWithLock(scheduleId).ifPresent(s -> {
            s.setGuardDecision(decision);
            s.setGuardReason(reason);
            s.setPolicyCode(policyCode);
            s.setStatus(RevenueRecognitionStatus.SKIPPED);
            scheduleRepository.save(s);
            log.debug("Schedule {} stamped {} / SKIPPED", scheduleId, decision);
        });
    }

    /**
     * Stamps guard decision on the schedule and leaves it {@code PENDING}, then
     * commits.  Called for {@link GuardDecision#DEFER}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stampDefer(Long scheduleId,
                           GuardDecision decision,
                           RecognitionPolicyCode policyCode,
                           String reason) {
        scheduleRepository.findByIdWithLock(scheduleId).ifPresent(s -> {
            s.setGuardDecision(decision);
            s.setGuardReason(reason);
            s.setPolicyCode(policyCode);
            // status intentionally left PENDING — eligible for re-evaluation
            scheduleRepository.save(s);
            log.debug("Schedule {} stamped {} / PENDING (deferred)", scheduleId, decision);
        });
    }

    /**
     * Stamps guard decision ({@link GuardDecision#ALLOW} or {@link GuardDecision#FLAG}),
     * sets {@code catchUpRun=true}, and commits.  Must be called <em>before</em>
     * {@link com.firstclub.ledger.revenue.service.RevenueRecognitionPostingService#postSingleRecognitionInRun}
     * so the committed guard fields are visible inside the posting service's own
     * {@code REQUIRES_NEW} transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stampAllowAndMarkCatchUp(Long scheduleId,
                                         GuardDecision decision,
                                         RecognitionPolicyCode policyCode,
                                         String reason) {
        scheduleRepository.findByIdWithLock(scheduleId).ifPresent(s -> {
            s.setGuardDecision(decision);
            s.setGuardReason(reason);
            s.setPolicyCode(policyCode);
            s.setCatchUpRun(true);
            scheduleRepository.save(s);
            log.debug("Schedule {} stamped {} / catchUpRun=true", scheduleId, decision);
        });
    }
}
