package com.firstclub.platform.concurrency.locking;

/**
 * Maps a named domain operation to its recommended {@link LockingStrategy}
 * together with the rationale for that choice.
 *
 * <p>Decisions are catalogued in {@link LockingDecisionCatalog}.
 *
 * @param domainOperation stable, dot-separated name of the operation
 *                        (e.g. {@code "subscription.renewal"})
 * @param strategy        the recommended concurrency strategy
 * @param rationale       human-readable explanation for the strategy choice
 */
public record LockingDecision(
        String domainOperation,
        LockingStrategy strategy,
        String rationale) {
}
