package com.firstclub.platform.concurrency.locking;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Authoritative catalog of recommended locking decisions for every major domain
 * write operation in this platform.
 *
 * <p>The catalog is intentionally declarative: it does <em>not</em> acquire any
 * locks — it records which strategy should be used and why, so engineers can look
 * up the rationale without hunting through service code.
 *
 * <h3>Adding a new entry</h3>
 * Add a constant in {@link LockingStrategy} if a new strategy is needed, add an
 * appropriate constant in
 * {@link com.firstclub.platform.concurrency.BusinessLockScope}, then add the new
 * operation here.
 */
@Component
public class LockingDecisionCatalog {

    private static final Map<String, LockingDecision> CATALOG = Map.of(

            "subscription.renewal",
            new LockingDecision(
                    "subscription.renewal",
                    LockingStrategy.DISTRIBUTED_FENCE,
                    "Renewal involves multi-step payment + state change. A distributed lock with "
                            + "fence token prevents double-charge if a node dies mid-renewal. "
                            + "OCC alone is insufficient because the payment call is external."),

            "refund.create",
            new LockingDecision(
                    "refund.create",
                    LockingStrategy.PESSIMISTIC_FOR_UPDATE,
                    "Must check cumulative refunded amount against payment intent; "
                            + "SELECT FOR UPDATE prevents concurrent refunds on the same intent "
                            + "from both reading a stale cumulative total."),

            "dispute.open",
            new LockingDecision(
                    "dispute.open",
                    LockingStrategy.PESSIMISTIC_FOR_UPDATE,
                    "Opening a dispute reserves funds and transitions the payment intent state; "
                            + "SELECT FOR UPDATE prevents a race between dispute and concurrent refund."),

            "invoice_sequence.increment",
            new LockingDecision(
                    "invoice_sequence.increment",
                    LockingStrategy.PESSIMISTIC_FOR_UPDATE,
                    "Invoice sequence numbers must be monotonically increasing with no gaps. "
                            + "Pessimistic lock on the sequence row guarantees serial increment "
                            + "without relying on DB sequence objects."),

            "outbox.poll",
            new LockingDecision(
                    "outbox.poll",
                    LockingStrategy.SKIP_LOCKED,
                    "Multiple scheduler/worker threads poll the outbox concurrently. "
                            + "SKIP LOCKED distributes work without contention — a row locked "
                            + "by one worker is simply skipped by others."),

            "scheduler.singleton",
            new LockingDecision(
                    "scheduler.singleton",
                    LockingStrategy.ADVISORY,
                    "Only one application instance should run a scheduled job at a time. "
                            + "An advisory lock (or ShedLock-style JobLock row) prevents "
                            + "duplicate execution across a horizontally-scaled cluster."),

            "projection.update",
            new LockingDecision(
                    "projection.update",
                    LockingStrategy.IDEMPOTENT_ASYNC,
                    "Projection rebuilds are designed to be safely re-applied. "
                            + "No strong lock is needed because the update is idempotent "
                            + "and eventual consistency is acceptable for read-side projections.")
    );

    /**
     * Returns the catalogued decision for the given domain operation, if present.
     *
     * @param domainOperation e.g. {@code "subscription.renewal"}
     * @return the decision, or empty if the operation has not been catalogued yet
     */
    public Optional<LockingDecision> forOperation(String domainOperation) {
        return Optional.ofNullable(CATALOG.get(domainOperation));
    }

    /**
     * Returns an unmodifiable view of all catalogued decisions keyed by operation name.
     */
    public Map<String, LockingDecision> allDecisions() {
        return Collections.unmodifiableMap(CATALOG);
    }
}
