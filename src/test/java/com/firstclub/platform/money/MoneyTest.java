package com.firstclub.platform.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Money")
class MoneyTest {

    // ── Factories ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ofMinor")
    class OfMinor {

        @Test
        @DisplayName("stores amount and currency")
        void storesAmountAndCurrency() {
            Money m = Money.ofMinor(CurrencyCode.INR, 10050L);
            assertThat(m.getCurrency()).isEqualTo(CurrencyCode.INR);
            assertThat(m.getAmountMinor()).isEqualTo(10050L);
        }

        @Test
        @DisplayName("allows zero")
        void allowsZero() {
            assertThat(Money.ofMinor(CurrencyCode.USD, 0L).isZero()).isTrue();
        }

        @Test
        @DisplayName("allows negative (e.g. refund representation)")
        void allowsNegative() {
            assertThat(Money.ofMinor(CurrencyCode.INR, -500L).isNegative()).isTrue();
        }
    }

    @Nested
    @DisplayName("ofMajor")
    class OfMajor {

        @Test
        @DisplayName("converts 100.50 INR → 10050 paise")
        void convertsDecimalMajorToMinor() {
            Money m = Money.ofMajor(CurrencyCode.INR, new BigDecimal("100.50"));
            assertThat(m.getAmountMinor()).isEqualTo(10050L);
        }

        @Test
        @DisplayName("half-even rounding: 0.005 → 0 (rounds to even 0, not 1)")
        void halfEvenRounding_roundsDown() {
            // 0.005 INR × 100 = 0.5 paise → rounds to 0 (even)
            Money m = Money.ofMajor(CurrencyCode.INR, new BigDecimal("0.005"));
            assertThat(m.getAmountMinor()).isEqualTo(0L);
        }

        @Test
        @DisplayName("half-even rounding: 0.015 → 2 (rounds to even 2)")
        void halfEvenRounding_roundsUp() {
            // 0.015 INR × 100 = 1.5 paise → rounds to 2 (even)
            Money m = Money.ofMajor(CurrencyCode.INR, new BigDecimal("0.015"));
            assertThat(m.getAmountMinor()).isEqualTo(2L);
        }

        @Test
        @DisplayName("throws on null amount")
        void throwsOnNullAmount() {
            assertThatThrownBy(() -> Money.ofMajor(CurrencyCode.INR, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("zero")
    class Zero {

        @Test
        @DisplayName("creates a zero-amount Money")
        void createsZeroMoney() {
            Money z = Money.zero(CurrencyCode.USD);
            assertThat(z.isZero()).isTrue();
            assertThat(z.getAmountMinor()).isZero();
            assertThat(z.getCurrency()).isEqualTo(CurrencyCode.USD);
        }
    }

    // ── Arithmetic ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("add")
    class Add {

        @Test
        @DisplayName("adds same-currency amounts")
        void addsSameCurrency() {
            Money a = Money.ofMinor(CurrencyCode.INR, 5000L);
            Money b = Money.ofMinor(CurrencyCode.INR, 2500L);
            assertThat(a.add(b).getAmountMinor()).isEqualTo(7500L);
        }

        @Test
        @DisplayName("throws CurrencyMismatchException on different currencies")
        void throwsOnCurrencyMismatch() {
            Money inr = Money.ofMinor(CurrencyCode.INR, 100L);
            Money usd = Money.ofMinor(CurrencyCode.USD, 100L);
            assertThatThrownBy(() -> inr.add(usd))
                    .isInstanceOf(CurrencyMismatchException.class)
                    .hasMessageContaining("INR")
                    .hasMessageContaining("USD");
        }
    }

    @Nested
    @DisplayName("subtract")
    class Subtract {

        @Test
        @DisplayName("subtracts same-currency amounts")
        void subtractsSameCurrency() {
            Money a = Money.ofMinor(CurrencyCode.INR, 5000L);
            Money b = Money.ofMinor(CurrencyCode.INR, 3000L);
            assertThat(a.subtract(b).getAmountMinor()).isEqualTo(2000L);
        }

        @Test
        @DisplayName("result can be negative (refund scenario)")
        void resultCanBeNegative() {
            Money a = Money.ofMinor(CurrencyCode.INR, 100L);
            Money b = Money.ofMinor(CurrencyCode.INR, 500L);
            assertThat(a.subtract(b).isNegative()).isTrue();
        }

        @Test
        @DisplayName("throws on currency mismatch")
        void throwsOnCurrencyMismatch() {
            assertThatThrownBy(() ->
                Money.ofMinor(CurrencyCode.INR, 100L).subtract(Money.ofMinor(CurrencyCode.EUR, 100L)))
                    .isInstanceOf(CurrencyMismatchException.class);
        }
    }

    @Nested
    @DisplayName("negate")
    class Negate {

        @Test
        @DisplayName("flips sign of positive amount")
        void negatesPositive() {
            assertThat(Money.ofMinor(CurrencyCode.INR, 100L).negate().getAmountMinor()).isEqualTo(-100L);
        }

        @Test
        @DisplayName("double-negation returns original value")
        void doubleNegationIsIdentity() {
            Money original = Money.ofMinor(CurrencyCode.USD, 999L);
            assertThat(original.negate().negate()).isEqualTo(original);
        }
    }

    // ── Predicates ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isNegative / isZero / isPositive are mutually exclusive")
    void predicatesMutuallyExclusive() {
        assertThat(Money.ofMinor(CurrencyCode.INR, -1L).isNegative()).isTrue();
        assertThat(Money.ofMinor(CurrencyCode.INR, -1L).isZero()).isFalse();
        assertThat(Money.ofMinor(CurrencyCode.INR, -1L).isPositive()).isFalse();

        assertThat(Money.ofMinor(CurrencyCode.INR, 0L).isZero()).isTrue();
        assertThat(Money.ofMinor(CurrencyCode.INR, 0L).isNegative()).isFalse();
        assertThat(Money.ofMinor(CurrencyCode.INR, 0L).isPositive()).isFalse();

        assertThat(Money.ofMinor(CurrencyCode.INR, 1L).isPositive()).isTrue();
        assertThat(Money.ofMinor(CurrencyCode.INR, 1L).isNegative()).isFalse();
        assertThat(Money.ofMinor(CurrencyCode.INR, 1L).isZero()).isFalse();
    }

    // ── Comparison ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("comparison operators")
    class Comparison {

        private final Money small  = Money.ofMinor(CurrencyCode.INR, 100L);
        private final Money medium = Money.ofMinor(CurrencyCode.INR, 500L);
        private final Money copy   = Money.ofMinor(CurrencyCode.INR, 500L);

        @Test void greaterThan()           { assertThat(medium.greaterThan(small)).isTrue(); }
        @Test void greaterThan_false()     { assertThat(small.greaterThan(medium)).isFalse(); }
        @Test void lessThan()              { assertThat(small.lessThan(medium)).isTrue(); }
        @Test void lessThan_false()        { assertThat(medium.lessThan(small)).isFalse(); }
        @Test void greaterThanOrEqualTo()  { assertThat(medium.greaterThanOrEqualTo(copy)).isTrue(); }
        @Test void lessThanOrEqualTo()     { assertThat(copy.lessThanOrEqualTo(medium)).isTrue(); }

        @Test
        @DisplayName("comparison throws on currency mismatch")
        void throwsOnCurrencyMismatch() {
            Money usd = Money.ofMinor(CurrencyCode.USD, 100L);
            assertThatThrownBy(() -> small.greaterThan(usd))
                    .isInstanceOf(CurrencyMismatchException.class);
        }
    }

    // ── Display ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("formatMajor")
    class FormatMajor {

        @Test
        @DisplayName("formats INR paise as rupee string")
        void formatsInrPaise() {
            assertThat(Money.ofMinor(CurrencyCode.INR, 10050L).formatMajor()).isEqualTo("₹100.50");
        }

        @Test
        @DisplayName("formats USD cents as dollar string")
        void formatsUsdCents() {
            assertThat(Money.ofMinor(CurrencyCode.USD, 199L).formatMajor()).isEqualTo("$1.99");
        }

        @Test
        @DisplayName("formats zero")
        void formatsZero() {
            assertThat(Money.ofMinor(CurrencyCode.INR, 0L).formatMajor()).isEqualTo("₹0.00");
        }
    }

    // ── equals / hashCode ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsHashCode {

        @Test
        @DisplayName("equal when currency and amount are both equal")
        void equalWhenSame() {
            Money a = Money.ofMinor(CurrencyCode.INR, 500L);
            Money b = Money.ofMinor(CurrencyCode.INR, 500L);
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("not equal when amounts differ")
        void notEqualDifferentAmount() {
            assertThat(Money.ofMinor(CurrencyCode.INR, 100L))
                    .isNotEqualTo(Money.ofMinor(CurrencyCode.INR, 200L));
        }

        @Test
        @DisplayName("not equal when currencies differ")
        void notEqualDifferentCurrency() {
            assertThat(Money.ofMinor(CurrencyCode.INR, 100L))
                    .isNotEqualTo(Money.ofMinor(CurrencyCode.USD, 100L));
        }

        @Test
        @DisplayName("not equal to null")
        void notEqualToNull() {
            assertThat(Money.ofMinor(CurrencyCode.INR, 100L)).isNotEqualTo(null);
        }
    }

    // ── toString ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("toString includes currency and minor amount")
    void toStringIncludesCurrencyAndAmount() {
        String s = Money.ofMinor(CurrencyCode.INR, 5000L).toString();
        assertThat(s).contains("INR").contains("5000");
    }
}
