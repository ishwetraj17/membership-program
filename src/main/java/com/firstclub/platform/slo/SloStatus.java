package com.firstclub.platform.slo;

/**
 * Operational status of a single SLO at a point in time.
 *
 * <pre>
 *   MEETING          — current value is at or above the target
 *   AT_RISK          — current value is between the at-risk threshold and the target
 *   BREACHED         — current value has fallen below the target (SLO violated)
 *   INSUFFICIENT_DATA — not enough data to compute a meaningful rate
 *                       (counters at zero since last restart, or no timer samples)
 * </pre>
 */
public enum SloStatus {
    MEETING,
    AT_RISK,
    BREACHED,
    INSUFFICIENT_DATA
}
