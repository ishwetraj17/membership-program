package com.firstclub.platform.version;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ApiVersion")
class ApiVersionTest {

    // ── Constants ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("V_2024_01 constant is non-null and has value 2024-01-01")
    void v2024_01_constantIsCorrect() {
        assertThat(ApiVersion.V_2024_01).isNotNull();
        assertThat(ApiVersion.V_2024_01.getValue()).isEqualTo("2024-01-01");
    }

    @Test
    @DisplayName("CURRENT constant is non-null")
    void current_constantIsNotNull() {
        assertThat(ApiVersion.CURRENT).isNotNull();
    }

    @Test
    @DisplayName("DEFAULT equals V_2024_01")
    void default_equalsV2024_01() {
        assertThat(ApiVersion.DEFAULT).isEqualTo(ApiVersion.V_2024_01);
    }

    @Test
    @DisplayName("V_2024_01 is before CURRENT")
    void v2024_01_isBeforeCurrent() {
        assertThat(ApiVersion.V_2024_01.isBefore(ApiVersion.CURRENT)).isTrue();
    }

    // ── fromString ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("fromString parses a valid YYYY-MM-DD version")
    void fromString_parsesValidVersion() {
        ApiVersion v = ApiVersion.fromString("2025-06-15");
        assertThat(v.getValue()).isEqualTo("2025-06-15");
    }

    @Test
    @DisplayName("fromString trims whitespace")
    void fromString_trimsWhitespace() {
        ApiVersion v = ApiVersion.fromString("  2025-06-15  ");
        assertThat(v.getValue()).isEqualTo("2025-06-15");
    }

    @Test
    @DisplayName("fromString throws on null")
    void fromString_throwsOnNull() {
        assertThatThrownBy(() -> ApiVersion.fromString(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("fromString throws on wrong format")
    void fromString_throwsOnWrongFormat() {
        assertThatThrownBy(() -> ApiVersion.fromString("v2025-01-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2025-01-01");
    }

    @Test
    @DisplayName("fromString throws on partial date")
    void fromString_throwsOnPartialDate() {
        assertThatThrownBy(() -> ApiVersion.fromString("2025-06"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── parseOrDefault ────────────────────────────────────────────────────────

    @Test
    @DisplayName("parseOrDefault returns DEFAULT for null input")
    void parseOrDefault_nullReturnsDefault() {
        assertThat(ApiVersion.parseOrDefault(null)).isEqualTo(ApiVersion.DEFAULT);
    }

    @Test
    @DisplayName("parseOrDefault returns DEFAULT for blank string")
    void parseOrDefault_blankReturnsDefault() {
        assertThat(ApiVersion.parseOrDefault("  ")).isEqualTo(ApiVersion.DEFAULT);
    }

    @Test
    @DisplayName("parseOrDefault returns DEFAULT for empty string")
    void parseOrDefault_emptyReturnsDefault() {
        assertThat(ApiVersion.parseOrDefault("")).isEqualTo(ApiVersion.DEFAULT);
    }

    @Test
    @DisplayName("parseOrDefault parses a valid string")
    void parseOrDefault_parsesValidString() {
        assertThat(ApiVersion.parseOrDefault("2025-06-15").getValue()).isEqualTo("2025-06-15");
    }

    // ── Comparison ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isBefore: earlier date is before later date")
    void isBefore_earlierBeforeLater() {
        ApiVersion early = ApiVersion.fromString("2024-01-01");
        ApiVersion late  = ApiVersion.fromString("2025-06-15");
        assertThat(early.isBefore(late)).isTrue();
        assertThat(late.isBefore(early)).isFalse();
    }

    @Test
    @DisplayName("isBefore: same version is not before itself")
    void isBefore_sameVersionIsFalse() {
        ApiVersion v = ApiVersion.fromString("2025-01-01");
        assertThat(v.isBefore(v)).isFalse();
    }

    @Test
    @DisplayName("isEqualTo: same date string is equal")
    void isEqualTo_sameDateIsEqual() {
        ApiVersion a = ApiVersion.fromString("2025-01-01");
        ApiVersion b = ApiVersion.fromString("2025-01-01");
        assertThat(a.isEqualTo(b)).isTrue();
    }

    @Test
    @DisplayName("isAfterOrEqual: later version is after earlier")
    void isAfterOrEqual_laterIsAfter() {
        ApiVersion early = ApiVersion.fromString("2024-01-01");
        ApiVersion late  = ApiVersion.fromString("2025-06-15");
        assertThat(late.isAfterOrEqual(early)).isTrue();
        assertThat(early.isAfterOrEqual(late)).isFalse();
    }

    @Test
    @DisplayName("isAfterOrEqual: same version is equal (not only after)")
    void isAfterOrEqual_sameVersionIsTrue() {
        ApiVersion v = ApiVersion.fromString("2025-01-01");
        assertThat(v.isAfterOrEqual(v)).isTrue();
    }

    // ── equals / hashCode ─────────────────────────────────────────────────────

    @Test
    @DisplayName("two versions with same string are equal")
    void equals_sameStringIsEqual() {
        assertThat(ApiVersion.fromString("2025-01-01"))
                .isEqualTo(ApiVersion.fromString("2025-01-01"));
    }

    @Test
    @DisplayName("two versions with different strings are not equal")
    void equals_differentStringsAreNotEqual() {
        assertThat(ApiVersion.fromString("2024-01-01"))
                .isNotEqualTo(ApiVersion.fromString("2025-01-01"));
    }

    @Test
    @DisplayName("hashCode contract: equal objects have equal hash codes")
    void hashCode_contract() {
        ApiVersion a = ApiVersion.fromString("2025-01-01");
        ApiVersion b = ApiVersion.fromString("2025-01-01");
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // ── toString ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("toString returns the YYYY-MM-DD string")
    void toString_returnsValue() {
        assertThat(ApiVersion.fromString("2025-06-15").toString()).isEqualTo("2025-06-15");
    }

    // ── Comparable ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("compareTo is consistent: earlier < later")
    void compareTo_earlierLessThanLater() {
        assertThat(ApiVersion.V_2024_01.compareTo(ApiVersion.CURRENT)).isNegative();
        assertThat(ApiVersion.CURRENT.compareTo(ApiVersion.V_2024_01)).isPositive();
    }

    @Test
    @DisplayName("compareTo: same version returns zero")
    void compareTo_sameVersionIsZero() {
        assertThat(ApiVersion.fromString("2025-01-01").compareTo(ApiVersion.fromString("2025-01-01"))).isZero();
    }
}
