package com.firstclub.outbox.scheduler;

import com.firstclub.outbox.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduled job that drives the transactional outbox delivery loop.
 *
 * <p>Every 30 seconds the poller:
 * <ol>
 *   <li>Locks a batch of NEW and due {@code outbox_events} rows (marks them
 *       PROCESSING in a single transaction).</li>
 *   <li>Processes each event in its own {@code REQUIRES_NEW} transaction so
 *       that a single handler failure never affects the rest of the batch.</li>
 * </ol>
 *
 * <p>The batch size is intentionally modest ({@value #BATCH_SIZE}) to keep
 * individual poll cycles short.  Increase for higher-volume deployments.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPoller {

    static final int BATCH_SIZE = 50;

    private final OutboxService outboxService;

    @Scheduled(fixedRate = 30_000, initialDelay = 10_000)
    public void poll() {
        List<Long> ids = outboxService.lockDueEvents(BATCH_SIZE);

        if (ids.isEmpty()) {
            return;
        }

        log.debug("OutboxPoller: processing {} event(s)", ids.size());

        for (Long id : ids) {
            try {
                outboxService.processSingleEvent(id);
            } catch (Exception e) {
                // processSingleEvent has its own transaction and error handling;
                // this outer catch is a safety net for unexpected infrastructure failures.
                log.error("Unexpected error dispatching outbox event {}", id, e);
            }
        }
    }

    /**
     * Runs every 5 minutes to recover outbox events that are stuck in PROCESSING
     * state (e.g. because the JVM crashed mid-processing).  Such events are reset
     * to NEW so the next poll cycle can re-acquire them.
     *
     * <p>Uses a 60-second initial delay so it doesn't fire at the same moment as
     * the main poll on application startup.
     */
    @Scheduled(fixedRate = 300_000, initialDelay = 60_000)
    public void recoverStaleLeases() {
        try {
            int recovered = outboxService.recoverStaleLeases();
            if (recovered > 0) {
                log.info("OutboxPoller: stale lease recovery reset {} event(s) to NEW", recovered);
            }
        } catch (Exception e) {
            log.error("OutboxPoller: stale lease recovery failed unexpectedly", e);
        }
    }
}
