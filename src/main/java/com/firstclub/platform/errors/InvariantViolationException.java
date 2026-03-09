package com.firstclub.platform.errors;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Thrown when a domain invariant — accounting, state, or business rule — is
 * violated in a way that signals a programming error or data integrity problem.
 *
 * <h3>Definition of an invariant</h3>
 * An invariant is a condition that must always be true in a correctly
 * functioning system.  Unlike user-input validation errors (400), invariant
 * violations are unexpected and indicate a bug or data corruption.
 *
 * <h3>Canonical examples</h3>
 * <ul>
 *   <li>A ledger entry whose debit total ≠ credit total.</li>
 *   <li>An invoice whose grand total ≠ sum of its line items.</li>
 *   <li>A refund that exceeds the original payment amount.</li>
 *   <li>A subscription that transitions to an impossible state.</li>
 * </ul>
 *
 * <h3>HTTP mapping</h3>
 * Maps to HTTP 500 (Internal Server Error) — this is a system error, not a
 * client error.  The integrity checker framework catches these and persists
 * findings so they can be investigated and repaired.
 */
public final class InvariantViolationException extends BaseDomainException {

    public InvariantViolationException(String errorCode, String message) {
        super(errorCode, message, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    public InvariantViolationException(String errorCode, String message,
                                        Map<String, Object> metadata) {
        super(errorCode, message, HttpStatus.INTERNAL_SERVER_ERROR, metadata);
    }

    // ── Factory helpers ──────────────────────────────────────────────────────

    /**
     * Ledger entry debit total does not equal credit total.
     *
     * @param debitMinor  total debit in minor units
     * @param creditMinor total credit in minor units
     */
    public static InvariantViolationException ledgerUnbalanced(long debitMinor, long creditMinor) {
        return new InvariantViolationException(
                "LEDGER_UNBALANCED",
                "Ledger entry does not balance: debit=" + debitMinor
                        + " minor != credit=" + creditMinor + " minor. "
                        + "Δ=" + Math.abs(debitMinor - creditMinor),
                Map.of("debitMinor",  debitMinor,
                       "creditMinor", creditMinor,
                       "deltaMinor",  Math.abs(debitMinor - creditMinor)));
    }

    /**
     * A ledger account would become negative after applying this entry.
     *
     * @param accountCode  chart-of-accounts code
     * @param balanceMinor resulting balance in minor units
     */
    public static InvariantViolationException negativeBalance(String accountCode,
                                                               long balanceMinor) {
        return new InvariantViolationException(
                "NEGATIVE_BALANCE",
                "Account '" + accountCode + "' balance would go negative: "
                        + balanceMinor + " minor.",
                Map.of("accountCode",   accountCode,
                       "balanceMinor",  balanceMinor));
    }

    /**
     * A refund would exceed the original payment amount.
     *
     * @param refundMinor     attempted refund amount in minor units
     * @param availableMinor  maximum refundable amount in minor units
     */
    public static InvariantViolationException refundExceedsPayment(long refundMinor,
                                                                    long availableMinor) {
        return new InvariantViolationException(
                "REFUND_EXCEEDS_PAYMENT",
                "Refund amount " + refundMinor
                        + " minor exceeds available refundable amount "
                        + availableMinor + " minor.",
                Map.of("refundMinor",    refundMinor,
                       "availableMinor", availableMinor));
    }
}
