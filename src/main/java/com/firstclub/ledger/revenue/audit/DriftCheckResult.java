package com.firstclub.ledger.revenue.audit;

import java.math.BigDecimal;

/**
 * Result of a revenue recognition drift check for a single invoice.
 *
 * <h3>What "drift" means</h3>
 * Drift occurs when the amount that <em>has been recognized</em> (sum of
 * {@code POSTED} schedule rows) does not match the amount that
 * <em>should have been recognized</em> (sum of all schedule rows for the
 * invoice, which must equal {@code invoice.grand_total}).
 *
 * <p>Drift also includes cases where past-due schedules remain {@code PENDING}
 * after their {@code recognition_date} — these represent a timing gap where
 * deferred revenue has not been unwound on schedule.
 *
 * @param invoiceId           the invoice under examination
 * @param scheduledTotal      sum of all schedule-row amounts (= invoice grand total)
 * @param recognizedTotal     sum of POSTED schedule-row amounts
 * @param delta               {@code recognizedTotal − scheduledTotal}; negative = under-recognized
 * @param pendingOverdueCount number of PENDING rows with {@code recognition_date < today}
 * @param hasDrift            {@code true} if delta ≠ 0 or pendingOverdueCount > 0
 */
public record DriftCheckResult(
        Long invoiceId,
        BigDecimal scheduledTotal,
        BigDecimal recognizedTotal,
        BigDecimal delta,
        long pendingOverdueCount,
        boolean hasDrift) {

    /**
     * Convenience factory that computes {@code delta} and {@code hasDrift}.
     *
     * @param invoiceId           the invoice
     * @param scheduledTotal      null-safe; defaults to {@link BigDecimal#ZERO}
     * @param recognizedTotal     null-safe; defaults to {@link BigDecimal#ZERO}
     * @param pendingOverdueCount count from repository
     * @return fully computed result
     */
    public static DriftCheckResult of(Long invoiceId,
                                      BigDecimal scheduledTotal,
                                      BigDecimal recognizedTotal,
                                      long pendingOverdueCount) {
        BigDecimal sched = scheduledTotal  != null ? scheduledTotal  : BigDecimal.ZERO;
        BigDecimal recog = recognizedTotal != null ? recognizedTotal : BigDecimal.ZERO;
        BigDecimal delta = recog.subtract(sched);
        boolean drift = delta.compareTo(BigDecimal.ZERO) != 0 || pendingOverdueCount > 0;
        return new DriftCheckResult(invoiceId, sched, recog, delta, pendingOverdueCount, drift);
    }
}
