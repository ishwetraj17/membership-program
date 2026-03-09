package com.firstclub.platform.scheduler.health;

/**
 * Health classification for a scheduled job.
 *
 * <ul>
 *   <li>{@link #HEALTHY}   — the scheduler ran within its expected interval.</li>
 *   <li>{@link #STALE}     — the last successful run is older than the expected
 *       interval; the scheduler may be stuck, mis-configured, or blocked on the
 *       advisory lock by a crashed node.</li>
 *   <li>{@link #NEVER_RAN} — no successful execution record exists for this
 *       scheduler; it has either never been deployed or has always failed/skipped.</li>
 * </ul>
 */
public enum SchedulerHealth {

    /**
     * The scheduler ran successfully within the expected cadence.
     * No action required.
     */
    HEALTHY,

    /**
     * The scheduler has not run successfully within its expected interval.
     * Investigate advisory lock leaks, node failures, or configuration changes.
     */
    STALE,

    /**
     * No successful execution record found.  The scheduler may never have run,
     * may always be losing the advisory lock, or the history table may have been
     * truncated.
     */
    NEVER_RAN
}
