package com.firstclub.platform.integrity;

/**
 * Severity classification for an integrity invariant.
 *
 * <ul>
 *   <li>{@code CRITICAL} — Financial data corruption; requires immediate page-out.</li>
 *   <li>{@code HIGH}     — Serious inconsistency; investigate within 1 hour.</li>
 *   <li>{@code MEDIUM}   — Business logic drift; investigate before next billing cycle.</li>
 *   <li>{@code LOW}      — Minor cosmetic / housekeeping issue; plan for next sprint.</li>
 * </ul>
 */
public enum IntegrityCheckSeverity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}
