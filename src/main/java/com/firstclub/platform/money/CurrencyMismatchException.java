package com.firstclub.platform.money;

/**
 * Thrown when two {@link Money} values of different currencies are combined
 * in arithmetic (add, subtract, compare).
 *
 * <p>Currency conversion is an explicit, audited operation — it must never
 * happen implicitly inside an arithmetic call.  This exception makes that
 * contract hard: callers are forced to convert first.
 */
public final class CurrencyMismatchException extends IllegalArgumentException {

    private final CurrencyCode expected;
    private final CurrencyCode actual;

    public CurrencyMismatchException(CurrencyCode expected, CurrencyCode actual) {
        super("Currency mismatch: cannot combine " + expected + " and " + actual
                + ". Convert to a common currency before performing arithmetic.");
        this.expected = expected;
        this.actual   = actual;
    }

    public CurrencyCode getExpected() { return expected; }
    public CurrencyCode getActual()   { return actual; }
}
