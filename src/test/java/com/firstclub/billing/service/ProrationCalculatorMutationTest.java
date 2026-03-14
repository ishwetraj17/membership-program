package com.firstclub.billing.service;

import com.firstclub.billing.dto.InvoiceLineDTO;
import com.firstclub.billing.entity.InvoiceLineType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Targeted mutation-killing tests for ProrationCalculator.compute().
 *
 * Addresses surviving PIT mutants:
 * - line 63: ConditionalsBoundaryMutator — totalDays > 0 && effectiveRemaining > 0
 * - line 63: RemoveConditionalMutator_ORDER_IF — replaced comparison with true
 */
@DisplayName("ProrationCalculator — Mutation-Killing Tests")
class ProrationCalculatorMutationTest {

    /**
     * Kills mutant: line 63 — ConditionalsBoundaryMutator — changed to >=
     * and RemoveConditionalMutator_ORDER_IF — removed totalDays > 0 check.
     *
     * When totalDays == 0, no PRORATION line should be generated (division
     * by zero guard). Only PLAN_CHARGE should be returned.
     */
    @Test
    @DisplayName("zero totalDays produces only PLAN_CHARGE, no proration")
    void compute_zeroTotalDays_noProrationLine() {
        ProrationCalculator calc = new ProrationCalculator(null, null);

        List<InvoiceLineDTO> lines = calc.compute(
                new BigDecimal("1000.00"),
                0L,  // zero total days
                15L,
                new BigDecimal("1500.00"));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getLineType()).isEqualTo(InvoiceLineType.PLAN_CHARGE);
        assertThat(lines.get(0).getAmount()).isEqualByComparingTo("1500.00");
    }

    /**
     * Kills mutant: line 63 — boundary: effectiveRemaining == 0 should not
     * generate a proration line (credit of $0 is meaningless).
     */
    @Test
    @DisplayName("zero remainingDays produces only PLAN_CHARGE, no proration")
    void compute_zeroRemaining_noProrationLine() {
        ProrationCalculator calc = new ProrationCalculator(null, null);

        List<InvoiceLineDTO> lines = calc.compute(
                new BigDecimal("1000.00"),
                30L,
                0L,  // zero remaining
                new BigDecimal("1500.00"));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getLineType()).isEqualTo(InvoiceLineType.PLAN_CHARGE);
    }

    /**
     * Verifies that exactly 1 day remaining produces a proration credit line.
     * This tests the boundary: effectiveRemaining > 0 when it's exactly 1.
     */
    @Test
    @DisplayName("one remaining day produces PRORATION credit + PLAN_CHARGE")
    void compute_oneRemainingDay_producesProration() {
        ProrationCalculator calc = new ProrationCalculator(null, null);

        List<InvoiceLineDTO> lines = calc.compute(
                new BigDecimal("3000.00"),
                30L,
                1L,
                new BigDecimal("4000.00"));

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).getLineType()).isEqualTo(InvoiceLineType.PRORATION);
        // 3000 * 1/30 = 100, negated = -100
        assertThat(lines.get(0).getAmount()).isEqualByComparingTo("-100.00");
        assertThat(lines.get(1).getLineType()).isEqualTo(InvoiceLineType.PLAN_CHARGE);
        assertThat(lines.get(1).getAmount()).isEqualByComparingTo("4000.00");
    }

    /**
     * Tests negative remainingDays — should be clamped to 0.
     */
    @Test
    @DisplayName("negative remainingDays clamped to zero, no proration")
    void compute_negativeRemaining_clampedToZero() {
        ProrationCalculator calc = new ProrationCalculator(null, null);

        List<InvoiceLineDTO> lines = calc.compute(
                new BigDecimal("1000.00"),
                30L,
                -5L,
                new BigDecimal("1500.00"));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getLineType()).isEqualTo(InvoiceLineType.PLAN_CHARGE);
    }

    /**
     * Tests remainingDays > totalDays — should be clamped to totalDays.
     */
    @Test
    @DisplayName("remainingDays > totalDays clamped to totalDays, full credit")
    void compute_remainingExceedsTotalDays_clampedToFull() {
        ProrationCalculator calc = new ProrationCalculator(null, null);

        List<InvoiceLineDTO> lines = calc.compute(
                new BigDecimal("1000.00"),
                30L,
                50L,  // > totalDays
                new BigDecimal("1500.00"));

        assertThat(lines).hasSize(2);
        // Full credit = 1000 * 30/30 = 1000
        assertThat(lines.get(0).getAmount()).isEqualByComparingTo("-1000.00");
    }
}
