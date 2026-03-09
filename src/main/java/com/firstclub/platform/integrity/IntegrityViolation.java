package com.firstclub.platform.integrity;

import lombok.Builder;
import lombok.Value;

/**
 * A single entity that violates an integrity invariant.
 */
@Value
@Builder
public class IntegrityViolation {

    /** Entity type label (e.g. "INVOICE", "PAYMENT", "LEDGER_ENTRY"). */
    String entityType;

    /** Primary key of the offending entity. */
    Long entityId;

    /** Human-readable description of why this entity violates the invariant. */
    String details;

    /**
     * Small JSON-serialisable preview of the entity fields relevant to the
     * violation (e.g. "grandTotal=100, lineSum=98").  May be null if there is
     * no meaningful extra context.
     */
    String preview;
}
