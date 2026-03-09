package com.firstclub.platform.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Platform-wide time abstraction.
 *
 * <p><strong>Rule:</strong> all production code must obtain the current time
 * via this interface. Direct calls to {@code Instant.now()},
 * {@code LocalDate.now()}, or {@code System.currentTimeMillis()} are
 * prohibited in business logic because they are untestable (cannot be
 * frozen or advanced in tests).
 *
 * <h3>Why this matters for a fintech system</h3>
 * <ul>
 *   <li>Subscription billing anchors, trial expiry, dunning schedules, and
 *       revenue recognition are all time-sensitive. Tests that rely on the
 *       real wall clock become flaky at midnight or across timezone changes.</li>
 *   <li>A fixed-clock implementation lets every test run at a known point in
 *       time, making assertion on dates deterministic.</li>
 *   <li>Audit and ledger entries must be stamped with a consistent, injectable
 *       time source so integration tests can validate timestamps.</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   @Service
 *   public class SubscriptionServiceImpl {
 *       private final ClockService clock;
 *
 *       public SubscriptionServiceImpl(ClockService clock) {
 *           this.clock = clock;
 *       }
 *
 *       public void activateSubscription(SubscriptionV2 sub) {
 *           sub.setActivatedAt(clock.now()); // ← injectable, testable
 *       }
 *   }
 * }</pre>
 *
 * @see SystemClockService
 */
public interface ClockService {

    /** UTC zone identifier used across the platform. */
    ZoneId UTC = ZoneId.of("UTC");

    /**
     * Current UTC {@link Instant}.  Use for timestamping audit entries,
     * ledger entries, and event logs.
     */
    Instant now();

    /**
     * Current UTC {@link LocalDate}.  Use for billing anchor calculations,
     * dunning schedule dates, and reporting period keys.
     */
    LocalDate todayUtc();

    /**
     * Current UTC {@link LocalDateTime}.  Use where a wall-clock date-time
     * without a zone offset is the idiomatic type (e.g., JPA
     * {@code @Column} mapped to {@code TIMESTAMP WITHOUT TIME ZONE}).
     */
    LocalDateTime nowUtc();

    /**
     * Underlying {@link Clock} instance.  Exposed for libraries that accept
     * a {@code Clock} directly (e.g., Micrometer, duration arithmetic).
     */
    Clock rawClock();
}
