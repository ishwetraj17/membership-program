package com.firstclub.platform.repair;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Immutable result returned by every {@link RepairAction#execute} invocation.
 */
@Value
@Builder
public class RepairActionResult {

    /** Repair key that produced this result. */
    String repairKey;

    /** Whether the action completed without errors. */
    boolean success;

    /** Whether this was a dry-run (no data was changed). */
    boolean dryRun;

    /** JSON snapshot of the entity/row before the repair (may be null if dry-run). */
    String beforeSnapshotJson;

    /** JSON snapshot of the entity/row after the repair (null on dry-run). */
    String afterSnapshotJson;

    /** Human-readable summary of what was changed (or would be changed on dry-run). */
    String details;

    /** Error message when {@code success==false}. */
    String errorMessage;

    /** Timestamp the action evaluated the target. */
    LocalDateTime evaluatedAt;
}
