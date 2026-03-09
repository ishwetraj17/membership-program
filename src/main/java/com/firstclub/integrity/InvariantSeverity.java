package com.firstclub.integrity;

/**
 * Operational severity attached to an {@link InvariantChecker}.
 *
 * <ul>
 *   <li>{@link #CRITICAL} — financial data inconsistency that must be fixed immediately
 *       (e.g. balance-sheet equation violated). Page on-call immediately.</li>
 *   <li>{@link #HIGH} — strong signal of a systemic bug (e.g. orphan ledger lines).
 *       Fix within hours; open P1 ticket.</li>
 *   <li>{@link #MEDIUM} — business-logic gap that needs attention (e.g. missing
 *       recognition schedule). Fix within a business day.</li>
 *   <li>{@link #LOW} — informational / hygiene check. Reviewed weekly.</li>
 * </ul>
 */
public enum InvariantSeverity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}
