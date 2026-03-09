package com.firstclub.outbox.ordering;

import com.firstclub.outbox.entity.OutboxEvent;
import com.firstclub.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Selects outbox events for processing using a two-tier priority scheme that
 * prevents retry starvation.
 *
 * <h3>The starvation problem</h3>
 * Without prioritisation, all due events are ordered globally by
 * {@code next_attempt_at}.  If a batch of old events is permanently failing
 * (e.g. a handler is broken), their {@code next_attempt_at} timestamps are in
 * the past and they will always sort before freshly published events — silently
 * blocking new work indefinitely.
 *
 * <h3>Solution: two queues, fresh first</h3>
 * <ol>
 *   <li>Fill up to {@code batchSize} with <em>fresh</em> events
 *       ({@code attempts = 0}) ordered by {@code created_at ASC}.</li>
 *   <li>Fill any remaining slots with <em>retry</em> events
 *       ({@code attempts &gt; 0}) ordered by {@code next_attempt_at ASC}.</li>
 * </ol>
 *
 * <p>Both partial queries use {@code FOR UPDATE SKIP LOCKED} and must execute
 * within an active transaction — call this component from a
 * {@code @Transactional} method.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPrioritySelector {

    private final OutboxEventRepository repository;

    /**
     * Selects and pessimistically locks up to {@code batchSize} events,
     * giving all capacity to fresh events first.
     *
     * @param batchSize maximum number of events to return
     * @return events in priority order (fresh before retries); may be empty
     */
    public List<OutboxEvent> selectAndLockBatch(int batchSize) {
        LocalDateTime now = LocalDateTime.now();

        List<OutboxEvent> fresh = repository.findAndLockFreshEvents(now, batchSize);
        int remaining = batchSize - fresh.size();

        List<OutboxEvent> retries = remaining > 0
                ? repository.findAndLockRetryEvents(now, remaining)
                : List.of();

        List<OutboxEvent> batch = new ArrayList<>(fresh);
        batch.addAll(retries);

        if (!batch.isEmpty()) {
            log.debug("PrioritySelector: {} fresh + {} retry = {} event(s) selected",
                    fresh.size(), retries.size(), batch.size());
        }
        return batch;
    }
}
