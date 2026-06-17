package com.firstclub.membership.initializer;

import com.firstclub.membership.event.OutboxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Polls the transactional outbox and dispatches pending domain events to the broker, and runs a
 * daily retention purge. Decoupled from the request path so a slow/unavailable broker never blocks writes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private final OutboxEventService outboxEventService;
    private final Clock clock;

    @Value("${outbox.retention-days:30}")
    private long retentionDays;

    @Scheduled(fixedDelayString = "${outbox.relay.delay-ms:10000}")
    public void relay() {
        int dispatched = outboxEventService.dispatchPending();
        if (dispatched > 0) {
            log.debug("Outbox relay dispatched {} event(s).", dispatched);
        }
    }

    /** Retention: drop long-dispatched events so the outbox table stays bounded. Daily at 03:30. */
    @Scheduled(cron = "0 30 3 * * *")
    public void purgeDispatched() {
        int purged = outboxEventService.purgeDispatched(LocalDateTime.now(clock).minusDays(retentionDays));
        if (purged > 0) {
            log.info("Outbox retention purged {} dispatched event(s).", purged);
        }
    }
}
