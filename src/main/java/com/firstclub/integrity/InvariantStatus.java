package com.firstclub.integrity;

/**
 * Coarse lifecycle state of a single invariant evaluation.
 */
public enum InvariantStatus {
    /** All checked rows satisfied the invariant. */
    PASS,
    /** One or more rows violated the invariant. */
    FAIL,
    /** The checker threw an unexpected exception and could not complete. */
    ERROR
}
