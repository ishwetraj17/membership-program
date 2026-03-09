package com.firstclub.recon;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Encapsulates the configurable timing-window policy used during reconciliation.
 *
 * <h3>The problem</h3>
 * Nightly reconciliation runs at 02:10 UTC and compares invoices and payments
 * for the previous calendar day.  Payments captured at 23:58 local time may
 * not reach the gateway settlement report until 00:02 the following day — a
 * 4-minute slip that would produce a false {@code INVOICE_NO_PAYMENT} mismatch
 * without any actual revenue loss.
 *
 * <h3>The solution</h3>
 * Extend the load window by {@code windowMinutes} on both ends of the day
 * boundary.  Mismatches whose invoice or payment timestamp falls inside the
 * extended margin are classified as
 * {@link com.firstclub.recon.classification.ReconExpectation#EXPECTED_TIMING_DIFFERENCE}
 * rather than a hard defect.
 *
 * <h3>Configuration</h3>
 * Override the default 30-minute window in {@code application.properties}:
 * <pre>
 *   recon.window.minutes=45
 * </pre>
 */
@Component
@Getter
public class ReconWindowPolicy {

    /** Default slack window around day boundaries, in minutes. */
    public static final int DEFAULT_WINDOW_MINUTES = 30;

    private final int windowMinutes;

    public ReconWindowPolicy(
            @Value("${recon.window.minutes:" + DEFAULT_WINDOW_MINUTES + "}")
            int windowMinutes) {
        if (windowMinutes < 0) {
            throw new IllegalArgumentException("recon.window.minutes must be >= 0, got: " + windowMinutes);
        }
        this.windowMinutes = windowMinutes;
    }

    /**
     * Computes the extended reconciliation window for {@code date}.
     *
     * <p>The window starts {@code windowMinutes} before midnight at the beginning
     * of the day and ends {@code windowMinutes} after midnight at the end of the day.
     *
     * @param date the business/reporting date
     * @return an immutable {@link ReconWindow} covering the extended range
     */
    public ReconWindow windowFor(LocalDate date) {
        LocalDateTime exactStart = date.atStartOfDay();
        LocalDateTime exactEnd   = date.atTime(LocalTime.MAX);
        return new ReconWindow(
                exactStart.minusMinutes(windowMinutes),
                exactEnd.plusMinutes(windowMinutes),
                exactStart,
                exactEnd);
    }

    /**
     * Returns {@code true} when {@code timestamp} is inside the window margin
     * (i.e. within {@code windowMinutes} of a day boundary for {@code date})
     * but outside the strict day range.
     *
     * <p>This indicates the event is a timing boundary case: it nominally belongs
     * to {@code date} but was recorded just outside the exact window.
     */
    public boolean isNearBoundary(LocalDate date, LocalDateTime timestamp) {
        ReconWindow w = windowFor(date);
        // Inside the slack zone but outside the strict day
        boolean beforeStrictStart = timestamp.isBefore(w.strictStart());
        boolean afterStrictEnd    = timestamp.isAfter(w.strictEnd());
        boolean insideExtended    = !timestamp.isBefore(w.extendedStart())
                                 && !timestamp.isAfter(w.extendedEnd());
        return insideExtended && (beforeStrictStart || afterStrictEnd);
    }

    // ── Nested ───────────────────────────────────────────────────────────────

    /**
     * Immutable representation of a reconciliation time window.
     *
     * @param extendedStart strict day start minus the window slack
     * @param extendedEnd   strict day end plus the window slack
     * @param strictStart   exact midnight at the beginning of the day
     * @param strictEnd     23:59:59.999999999 at the end of the day
     */
    public record ReconWindow(
            LocalDateTime extendedStart,
            LocalDateTime extendedEnd,
            LocalDateTime strictStart,
            LocalDateTime strictEnd) {

        /** Returns {@code true} if {@code ts} falls anywhere in the extended window. */
        public boolean contains(LocalDateTime ts) {
            return !ts.isBefore(extendedStart) && !ts.isAfter(extendedEnd);
        }
    }
}
