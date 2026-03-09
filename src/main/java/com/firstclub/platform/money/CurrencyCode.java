package com.firstclub.platform.money;

/**
 * Supported ISO 4217 currency codes.
 *
 * <p>Each constant carries the minor-unit scale (number of decimal places) so
 * callers can convert between major and minor units without hard-coding
 * divisors throughout the codebase.
 *
 * <h3>Why minor units?</h3>
 * <ul>
 *   <li>All hot-path financial columns in PostgreSQL are stored as
 *       {@code BIGINT} minor units (paise for INR, cents for USD) to avoid
 *       floating-point precision errors entirely.</li>
 *   <li>{@code long} arithmetic is exact; no rounding mode decisions needed
 *       for addition and subtraction.</li>
 *   <li>Division (e.g., computing a per-day proration amount) still requires
 *       {@link java.math.BigDecimal} with an explicit rounding mode —
 *       {@link Money#ofMajor} handles that at the boundary.</li>
 * </ul>
 */
public enum CurrencyCode {

    /** Indian Rupee — 100 paise per rupee. */
    INR(2, "₹"),

    /** US Dollar — 100 cents per dollar. */
    USD(2, "$"),

    /** Euro — 100 cents per euro. */
    EUR(2, "€");

    /** Number of decimal places between major and minor units. */
    private final int minorUnitScale;

    /** Display symbol, for logging and documentation ONLY. Never use in business logic. */
    private final String symbol;

    CurrencyCode(int minorUnitScale, String symbol) {
        this.minorUnitScale = minorUnitScale;
        this.symbol = symbol;
    }

    /** @return decimal places (e.g. 2 for INR, USD, EUR). */
    public int minorUnitScale() {
        return minorUnitScale;
    }

    /** @return human-readable symbol, for logs/docs only. */
    public String symbol() {
        return symbol;
    }

    /**
     * Number of minor units per major unit.
     * <p>E.g., 100 for INR (100 paise = 1 rupee).
     */
    public long minorUnitsPerMajor() {
        return (long) Math.pow(10, minorUnitScale);
    }
}
