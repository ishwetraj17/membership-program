package com.firstclub.payments.entity;

/**
 * Capture mode for a {@link PaymentIntentV2}.
 *
 * <ul>
 *   <li>{@code AUTO} – authorize and capture in one step.</li>
 *   <li>{@code MANUAL} – authorize only; explicit capture required later.</li>
 * </ul>
 */
public enum CaptureMode {
    /** Authorize and capture in a single request. */
    AUTO,
    /** Authorize only; merchant must issue an explicit capture later. */
    MANUAL
}
