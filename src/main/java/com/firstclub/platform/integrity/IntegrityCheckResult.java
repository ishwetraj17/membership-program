package com.firstclub.platform.integrity;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Immutable result returned by a single {@link IntegrityChecker} invocation.
 */
@Value
@Builder
public class IntegrityCheckResult {

    /** Unique identifier for the invariant that was checked. */
    String invariantKey;

    /** Severity of a violation for this invariant. */
    IntegrityCheckSeverity severity;

    /** {@code true} if no violations were found. */
    boolean passed;

    /** Total number of violations found (may exceed {@code violations.size()} if
     *  the list is capped for preview purposes). */
    int violationCount;

    /**
     * Up to 50 representative violations for inspection.
     * Intentionally capped to avoid unbounded payloads.
     */
    List<IntegrityViolation> violations;

    /** Human-readable summary of the check outcome. */
    String details;

    /**
     * Repair action key referencing an entry in the manual-repair actions
     * registry ({@code /docs/operations/05-manual-repair-actions.md}).
     * Null when the check passed or no automated repair is available.
     */
    String suggestedRepairKey;

    /** Wall-clock time when the check completed. */
    LocalDateTime checkedAt;
}
