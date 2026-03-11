package com.firstclub.reporting.projections.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Result of a single projection consistency check.
 * Returned by {@link com.firstclub.reporting.projections.service.ProjectionConsistencyChecker}.
 */
@Data
@Builder
public class ConsistencyReport {

    /** Identifies which projection and key was checked. */
    private String projectionName;

    /** String representation of the composite key that was sampled. */
    private String key;

    /** True when projection value matches the live source query. */
    private boolean consistent;

    /** The value held in the projection (may be a counter or amount). */
    private long projectionValue;

    /** The value computed directly from the authoritative source. */
    private long sourceValue;

    /** {@code sourceValue - projectionValue}; zero when consistent. */
    private long delta;

    /** Human-readable description of which field was compared. */
    private String checkedField;
}
