package com.firstclub.platform.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable, type-safe money value object.
 *
 * <h3>Storage model</h3>
 * Amounts are held as {@code long amountMinor} — the smallest indivisible
 * unit of the currency (paise for INR, cents for USD/EUR).  This matches the
 * PostgreSQL {@code BIGINT} columns used in all hot-path financial tables.
 *
 * <h3>No floating-point, ever</h3>
 * {@code double} and {@code float} are forbidden for money.  Addition and
 * subtraction on {@code long} are exact.  Division (e.g., prorating a price
 * over days) must cross a {@link BigDecimal} boundary with an explicit
 * {@link RoundingMode}; see {@link #ofMajor}.
 *
 * <h3>Currency safety</h3>
 * Every arithmetic and comparison method calls {@link #assertSameCurrency} to
 * prevent silent cross-currency contamination.  Conversion is always explicit.
 *
 * <h3>Immutability</h3>
 * Every method that would mutate returns a new {@code Money} instance.
 * This class is safe to use as a value in collections, caches, and
 * concurrent contexts without extra synchronization.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   Money price   = Money.ofMinor(CurrencyCode.INR, 49900L); // ₹499.00
 *   Money tax     = Money.ofMinor(CurrencyCode.INR, 8982L);  // ₹89.82
 *   Money total   = price.add(tax);                           // ₹588.82
 *
 *   Money discount = Money.ofMajor(CurrencyCode.INR, new BigDecimal("10.00"));
 *   Money net      = total.subtract(discount);                // ₹578.82
 * }</pre>
 */
public final class Money {

    private final CurrencyCode currency;
    private final long amountMinor;

    private Money(CurrencyCode currency, long amountMinor) {
        this.currency    = Objects.requireNonNull(currency, "currency must not be null");
        this.amountMinor = amountMinor;
    }

    // ── Factory methods ──────────────────────────────────────────────────────

    /**
     * Create from minor units (e.g., paise for INR).
     * Negative amounts are permitted — they represent credits or refunds.
     */
    public static Money ofMinor(CurrencyCode currency, long amountMinor) {
        return new Money(currency, amountMinor);
    }

    /**
     * Create from a major-unit {@link BigDecimal}.
     *
     * <p>The conversion multiplies by the currency's minor-unit factor and
     * rounds with {@link RoundingMode#HALF_EVEN} (banker's rounding) to
     * avoid cumulative bias in batch operations.
     *
     * <p>Example: {@code Money.ofMajor(INR, new BigDecimal("499.99"))} →
     * {@code Money{INR 49999 minor}}.
     *
     * @throws ArithmeticException if the converted value overflows {@code long}.
     */
    public static Money ofMajor(CurrencyCode currency, BigDecimal amountMajor) {
        Objects.requireNonNull(amountMajor, "amountMajor must not be null");
        BigDecimal scale = BigDecimal.valueOf(currency.minorUnitsPerMajor());
        long minor = amountMajor
                .multiply(scale)
                .setScale(0, RoundingMode.HALF_EVEN)
                .longValueExact();               // throws on overflow
        return new Money(currency, minor);
    }

    /** Zero amount in the given currency. */
    public static Money zero(CurrencyCode currency) {
        return new Money(currency, 0L);
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public CurrencyCode getCurrency()    { return currency; }
    public long         getAmountMinor() { return amountMinor; }

    // ── Arithmetic ───────────────────────────────────────────────────────────

    /**
     * Returns {@code this + other}.
     *
     * @throws CurrencyMismatchException if currencies differ.
     */
    public Money add(Money other) {
        assertSameCurrency(other);
        return new Money(currency, amountMinor + other.amountMinor);
    }

    /**
     * Returns {@code this - other}.
     *
     * @throws CurrencyMismatchException if currencies differ.
     */
    public Money subtract(Money other) {
        assertSameCurrency(other);
        return new Money(currency, amountMinor - other.amountMinor);
    }

    /** Returns a new Money with the amount negated (sign flipped). */
    public Money negate() {
        return new Money(currency, -amountMinor);
    }

    /** Returns absolute value. */
    public Money abs() {
        return amountMinor < 0 ? negate() : this;
    }

    // ── Predicates ───────────────────────────────────────────────────────────

    public boolean isNegative()  { return amountMinor < 0; }
    public boolean isZero()      { return amountMinor == 0; }
    public boolean isPositive()  { return amountMinor > 0; }

    /**
     * @throws CurrencyMismatchException if currencies differ.
     */
    public boolean greaterThan(Money other) {
        assertSameCurrency(other);
        return amountMinor > other.amountMinor;
    }

    public boolean greaterThanOrEqualTo(Money other) {
        assertSameCurrency(other);
        return amountMinor >= other.amountMinor;
    }

    public boolean lessThan(Money other) {
        assertSameCurrency(other);
        return amountMinor < other.amountMinor;
    }

    public boolean lessThanOrEqualTo(Money other) {
        assertSameCurrency(other);
        return amountMinor <= other.amountMinor;
    }

    // ── Display (non-authoritative) ──────────────────────────────────────────

    /**
     * Human-readable major-unit format.
     *
     * <p><strong>WARNING:</strong> use for logs, documentation, and error
     * messages ONLY.  Never use this string for persistence, API serialisation,
     * or business logic.  The authoritative value is {@link #getAmountMinor()}.
     *
     * <p>Example: {@code Money{INR 49900 minor}.formatMajor()} → {@code "₹499.00"}.
     */
    public String formatMajor() {
        BigDecimal major = BigDecimal.valueOf(amountMinor)
                .divide(BigDecimal.valueOf(currency.minorUnitsPerMajor()),
                        currency.minorUnitScale(),
                        RoundingMode.UNNECESSARY);
        return currency.symbol() + major.toPlainString();
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void assertSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (this.currency != other.currency) {
            throw new CurrencyMismatchException(this.currency, other.currency);
        }
    }

    // ── Object overrides ────────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money m)) return false;
        return amountMinor == m.amountMinor && currency == m.currency;
    }

    @Override
    public int hashCode() {
        return Objects.hash(currency, amountMinor);
    }

    /**
     * Structured string for logging — shows minor units explicitly to prevent
     * confusion about what the number represents.
     */
    @Override
    public String toString() {
        return "Money{" + currency + " " + amountMinor + " minor}";
    }
}
