package com.firstclub.ledger.revenue;

import com.firstclub.ledger.revenue.entity.RevenueRecognitionSchedule;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Allocates a total invoice amount across the daily revenue recognition schedule.
 *
 * <h3>Rounding rule</h3>
 * The per-day amount is computed using {@link RoundingMode#HALF_UP} at scale 4.
 * The <em>last</em> schedule row absorbs the rounding remainder so that:
 * <pre>
 *   sum(schedule.amount for all rows) == totalAmount   (exact)
 * </pre>
 *
 * <h3>Minor units</h3>
 * Each row also records {@code expectedAmountMinor} (amount × 100, rounded to
 * nearest integer) and — for the last row — {@code roundingAdjustmentMinor}
 * so that integer-arithmetic reconciliation tools can verify totals without
 * floating-point error.
 */
@Component
public class RevenueScheduleAllocator {

    /**
     * Allocates {@code totalAmount} across each calendar day in
     * {@code [periodStart, periodEnd)}.
     *
     * @param invoiceId      the invoice being scheduled
     * @param merchantId     owning merchant (tenant scope)
     * @param subscriptionId linked subscription
     * @param totalAmount    gross amount to spread (must be &gt; 0)
     * @param currency       ISO currency code
     * @param periodStart    first day of service (inclusive)
     * @param periodEnd      last day of service (exclusive — same convention as
     *                       {@link ChronoUnit#DAYS#between(Temporal, Temporal)})
     * @param fingerprint    generation fingerprint shared by all rows in this set
     * @param catchUpRun     {@code true} when regenerated via a repair operation
     * @return ordered list of unsaved schedule rows; last row has rounding absorbed
     */
    public List<RevenueRecognitionSchedule> allocate(
            Long invoiceId,
            Long merchantId,
            Long subscriptionId,
            BigDecimal totalAmount,
            String currency,
            LocalDate periodStart,
            LocalDate periodEnd,
            String fingerprint,
            boolean catchUpRun) {

        long numDays = ChronoUnit.DAYS.between(periodStart, periodEnd);
        if (numDays <= 0) {
            throw new IllegalArgumentException(
                    "Allocation period must be at least 1 day; got numDays=" + numDays
                    + " for [" + periodStart + ", " + periodEnd + ")");
        }

        // Per-day decimal amount (scale 4, HALF_UP) — same algorithm as legacy impl
        BigDecimal dailyAmount = totalAmount.divide(
                BigDecimal.valueOf(numDays), 4, RoundingMode.HALF_UP);

        // Last-day decimal = total − sum of all other rows (absorbs every rounding penny)
        BigDecimal lastDayAmount = totalAmount.subtract(
                dailyAmount.multiply(BigDecimal.valueOf(numDays - 1)));

        // Per-day minor units (scale 2 = paise/cents)
        long baseMinor = toMinor(dailyAmount);
        long lastMinor = toMinor(lastDayAmount);
        long roundingAdj = lastMinor - baseMinor;  // 0 when no rounding drift

        String resolvedCurrency = currency != null ? currency : "INR";

        List<RevenueRecognitionSchedule> rows = new ArrayList<>((int) numDays);
        for (long i = 0; i < numDays; i++) {
            boolean isLast = (i == numDays - 1);
            BigDecimal slice = isLast ? lastDayAmount : dailyAmount;
            long sliceMinor = isLast ? lastMinor : baseMinor;
            long adj = isLast ? roundingAdj : 0L;

            rows.add(RevenueRecognitionSchedule.builder()
                    .merchantId(merchantId)
                    .subscriptionId(subscriptionId)
                    .invoiceId(invoiceId)
                    .recognitionDate(periodStart.plusDays(i))
                    .amount(slice)
                    .currency(resolvedCurrency)
                    .status(RevenueRecognitionStatus.PENDING)
                    .expectedAmountMinor(sliceMinor)
                    .roundingAdjustmentMinor(adj)
                    .generationFingerprint(fingerprint)
                    .catchUpRun(catchUpRun)
                    .build());
        }
        return rows;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Converts a decimal amount to minor units (× 100, rounded to nearest int). */
    private static long toMinor(BigDecimal amount) {
        return amount.scaleByPowerOfTen(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }
}
