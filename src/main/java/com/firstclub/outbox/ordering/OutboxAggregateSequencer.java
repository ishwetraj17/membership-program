package com.firstclub.outbox.ordering;

import com.firstclub.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assigns per-aggregate sequence numbers to outbox events.
 *
 * <h3>Why per-aggregate ordering matters</h3>
 * Events for the same aggregate (e.g. a subscription) must be consumed in the
 * order they were produced to maintain correct state.  Two concurrent publishes
 * for the same aggregate could otherwise be processed out-of-order by separate
 * poller threads.  Storing a monotonically increasing {@code aggregate_sequence}
 * on each event allows consumers to detect and enforce ordering.
 *
 * <h3>Usage</h3>
 * Call {@link #nextSequence(String, String)} from inside the same transaction
 * that persists the new event.  The {@code MANDATORY} propagation ensures this
 * is never called outside a transaction, preventing sequence gaps under
 * concurrent inserts.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxAggregateSequencer {

    private final OutboxEventRepository repository;

    /**
     * Returns the next {@code aggregate_sequence} value for the given aggregate
     * instance.  Computes {@code MAX(aggregate_sequence) + 1} for the
     * ({@code aggregateType}, {@code aggregateId}) pair.  Returns {@code 1} if
     * no prior events exist for this aggregate.
     *
     * <p>Must be invoked within an active transaction
     * ({@code Propagation.MANDATORY}).
     *
     * @param aggregateType logical entity type, e.g. {@code "Subscription"}
     * @param aggregateId   primary key of the aggregate as a string
     * @return monotonically increasing sequence &ge; 1
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long nextSequence(String aggregateType, String aggregateId) {
        long next = repository.findMaxAggregateSequence(aggregateType, aggregateId)
                .map(max -> max + 1L)
                .orElse(1L);
        log.trace("AggregateSequencer: {}/{} → seq {}", aggregateType, aggregateId, next);
        return next;
    }
}
