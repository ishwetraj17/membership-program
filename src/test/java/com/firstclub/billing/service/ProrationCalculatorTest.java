package com.firstclub.billing.service;

import com.firstclub.billing.dto.InvoiceLineDTO;
import com.firstclub.billing.entity.InvoiceLineType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link ProrationCalculator#compute}.
 *
 * <p>No Spring context is needed — {@code compute()} is a stateless method
 * and the repositories injected into {@link ProrationCalculator} are never
 * accessed.  We therefore pass {@code null} for both dependencies.
 */
@DisplayName("ProrationCalculator — compute()")
class ProrationCalculatorTest {

    private ProrationCalculator calculator;

    @BeforeEach
    void setUp() {
        // Repositories are null — only compute() is exercised here (DB-free)
        calculator = new ProrationCalculator(null, null);
    }

    // -------------------------------------------------------------------------
    // Test 1 — standard halfway proration
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("halfway through period: credit = half of current plan, charge = full new plan")
    void compute_halfwayThroughPeriod_returnsCorrectLines() {
        // current plan: 1200 INR / 30-day period, 15 days remain
        // new plan: 2400 INR
        List<InvoiceLineDTO> lines = calculator.compute(
                new BigDecimal("1200.00"), 30, 15, new BigDecimal("2400.00"));

        assertThat(lines).hasSize(2);

        InvoiceLineDTO credit = lines.get(0);
        assertThat(credit.getLineType()).isEqualTo(InvoiceLineType.PRORATION);
        // 1200 × 15 / 30 = 600.00 → stored as negative credit
        assertThat(credit.getAmount()).isEqualByComparingTo(new BigDecimal("-600.00"));

        InvoiceLineDTO charge = lines.get(1);
        assertThat(charge.getLineType()).isEqualTo(InvoiceLineType.PLAN_CHARGE);
        assertThat(charge.getAmount()).isEqualByComparingTo(new BigDecimal("2400.00"));
    }

    // -------------------------------------------------------------------------
    // Test 2 — no remaining days
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("zero remaining days: no proration credit, only new plan charge")
    void compute_zeroRemainingDays_onlyPlanChargeReturned() {
        List<InvoiceLineDTO> lines = calculator.compute(
                new BigDecimal("1200.00"), 30, 0, new BigDecimal("800.00"));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getLineType()).isEqualTo(InvoiceLineType.PLAN_CHARGE);
        assertThat(lines.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("800.00"));
    }

    // -------------------------------------------------------------------------
    // Test 3 — full period remaining (first-day upgrade)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("full period remaining: full credit applied, net = newPlanPrice - currentPlanPrice")
    void compute_fullPeriodRemaining_fullCreditPlusNewCharge() {
        // upgrading on day 0: credit = 1200 (entire period), charge = 1800
        List<InvoiceLineDTO> lines = calculator.compute(
                new BigDecimal("1200.00"), 30, 30, new BigDecimal("1800.00"));

        BigDecimal net = lines.stream()
                .map(InvoiceLineDTO::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // net = -1200 + 1800 = 600
        assertThat(net).isEqualByComparingTo(new BigDecimal("600.00"));
    }

    // -------------------------------------------------------------------------
    // Test 4 — downgrade where credit exceeds new plan (net < 0)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("large credit plus small new charge: net total is negative (caller clamps to 0)")
    void compute_creditExceedsNewPlanPrice_netIsNegative() {
        // current: 3000 INR, 25 of 30 days remaining
        // credit = 3000 × 25/30 = 2500  → -2500
        // new plan charge = 500
        // net = -2000
        List<InvoiceLineDTO> lines = calculator.compute(
                new BigDecimal("3000.00"), 30, 25, new BigDecimal("500.00"));

        BigDecimal net = lines.stream()
                .map(InvoiceLineDTO::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(net).isLessThan(BigDecimal.ZERO);

        // Verify the exact proration credit
        InvoiceLineDTO credit = lines.get(0);
        assertThat(credit.getAmount()).isEqualByComparingTo(new BigDecimal("-2500.00"));
    }

    // -------------------------------------------------------------------------
    // Test 5 — negative remainingDays value is clamped to 0
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("negative remainingDays is clamped to zero — only new plan charge")
    void compute_negativeRemainingDays_treatedAsZero() {
        List<InvoiceLineDTO> lines = calculator.compute(
                new BigDecimal("999.00"), 30, -5, new BigDecimal("499.00"));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getLineType()).isEqualTo(InvoiceLineType.PLAN_CHARGE);
    }
}
