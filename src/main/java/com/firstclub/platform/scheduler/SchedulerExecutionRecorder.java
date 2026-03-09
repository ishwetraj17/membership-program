package com.firstclub.platform.scheduler;

import com.firstclub.platform.lock.redis.LockOwnerIdentityProvider;
import com.firstclub.platform.scheduler.entity.SchedulerExecutionHistory;
import com.firstclub.platform.scheduler.repository.SchedulerExecutionHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Records scheduler execution lifecycle events to {@link SchedulerExecutionHistory}.
 *
 * <h3>Usage pattern</h3>
 * <pre>
 *   Long runId = recorder.recordStarted("subscription-renewal");
 *   try {
 *       int count = doWork();
 *       recorder.recordSuccess(runId, count);
 *   } catch (Exception ex) {
 *       recorder.recordFailure(runId, ex);
 *       throw ex;
 *   }
 * </pre>
 *
 * <h3>Transaction propagation</h3>
 * Each write uses {@link Propagation#REQUIRES_NEW} so that recorder writes
 * are not rolled back when the scheduler's own transaction is rolled back.
 * This ensures that failure executions are always visible in the history table.
 *
 * <h3>DB time</h3>
 * {@link #recordStarted} captures {@link Instant#now()} via the JVM clock.
 * For strict TIMESTAMP ordering that does not depend on JVM clock drift,
 * callers may pass a DB-side {@code NOW()} via a raw query; in practice the
 * JVM clock is accurate enough for scheduler diagnostics.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchedulerExecutionRecorder {

    private final SchedulerExecutionHistoryRepository historyRepo;
    private final LockOwnerIdentityProvider ownerIdentity;

    /**
     * Writes a {@code RUNNING} record for the named scheduler and returns its
     * generated ID for later completion/failure linkage.
     *
     * @param schedulerName logical scheduler name
     * @return the ID of the persisted history row
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordStarted(String schedulerName) {
        SchedulerExecutionHistory row = SchedulerExecutionHistory.builder()
                .schedulerName(schedulerName)
                .nodeId(ownerIdentity.getInstanceId())
                .startedAt(Instant.now())
                .status(SchedulerExecutionHistory.STATUS_RUNNING)
                .build();
        row = historyRepo.save(row);
        log.info("[SCHEDULER-RECORDER] Started scheduler={} nodeId={} runId={}",
                schedulerName, row.getNodeId(), row.getId());
        return row.getId();
    }

    /**
     * Updates the history row to {@code SUCCESS} and sets the processed item count.
     *
     * @param runId          ID returned by {@link #recordStarted}
     * @param processedCount number of domain objects processed this cycle
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long runId, int processedCount) {
        historyRepo.findById(runId).ifPresentOrElse(row -> {
            row.setStatus(SchedulerExecutionHistory.STATUS_SUCCESS);
            row.setCompletedAt(Instant.now());
            row.setProcessedCount(processedCount);
            historyRepo.save(row);
            log.info("[SCHEDULER-RECORDER] Success scheduler={} runId={} processed={}",
                    row.getSchedulerName(), runId, processedCount);
        }, () -> log.warn("[SCHEDULER-RECORDER] recordSuccess: runId={} not found", runId));
    }

    /**
     * Updates the history row to {@code FAILED} and stores a truncated error message.
     *
     * @param runId ID returned by {@link #recordStarted}
     * @param ex    exception that caused the failure
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long runId, Throwable ex) {
        historyRepo.findById(runId).ifPresentOrElse(row -> {
            String message = ex.getMessage() != null
                    ? ex.getMessage().substring(0, Math.min(4000, ex.getMessage().length()))
                    : ex.getClass().getSimpleName();
            row.setStatus(SchedulerExecutionHistory.STATUS_FAILED);
            row.setCompletedAt(Instant.now());
            row.setErrorMessage(message);
            historyRepo.save(row);
            log.warn("[SCHEDULER-RECORDER] Failed scheduler={} runId={} error={}",
                    row.getSchedulerName(), runId, message);
        }, () -> log.warn("[SCHEDULER-RECORDER] recordFailure: runId={} not found", runId));
    }

    /**
     * Writes a terminal {@code SKIPPED} record for a scheduler that did not
     * acquire the advisory lock or failed the primary-only check.
     *
     * <p>No corresponding {@code recordSuccess}/{@code recordFailure} call is
     * needed — the row is written fully complete in one step.
     *
     * @param schedulerName logical scheduler name
     * @param reason        short human-readable reason, e.g. {@code "lock-busy"} or
     *                      {@code "not-primary"}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSkipped(String schedulerName, String reason) {
        Instant now = Instant.now();
        SchedulerExecutionHistory row = SchedulerExecutionHistory.builder()
                .schedulerName(schedulerName)
                .nodeId(ownerIdentity.getInstanceId())
                .startedAt(now)
                .completedAt(now)
                .status(SchedulerExecutionHistory.STATUS_SKIPPED)
                .errorMessage(reason)
                .build();
        historyRepo.save(row);
        log.info("[SCHEDULER-RECORDER] Skipped scheduler={} nodeId={} reason={}",
                schedulerName, row.getNodeId(), reason);
    }
}
