package com.firstclub.integrity;

import lombok.Builder;
import lombok.Getter;

/**
 * One row of evidence produced when an invariant check fails.
 *
 * <p>An {@link InvariantResult} can contain zero or more violations. Each violation
 * identifies the offending entity and provides enough context to investigate and repair.
 */
@Getter
@Builder
public class InvariantViolation {

    /** Human-readable entity type (e.g. "Payment", "LedgerLine"). */
    private final String entityType;

    /** Entity identifier as a string (e.g. "42", "system"). */
    private final String entityId;

    /** A human-readable description of what invariant was breached. */
    private final String description;

    /** Concrete, actionable step the operator should take to fix this row. */
    private final String suggestedRepairAction;

    /** Convenience — returns {@code entityType + "#" + entityId} for list fields in API responses. */
    public String toAffectedEntityString() {
        return entityType + "#" + entityId;
    }
}
