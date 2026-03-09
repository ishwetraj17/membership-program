package com.firstclub.platform.time;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Default {@link ClockService} implementation backed by the system UTC clock.
 *
 * <p>This bean is marked {@link Primary} so it is the default Spring injection
 * target in production.  Tests that need deterministic time should inject a
 * {@link SystemClockService#SystemClockService(Clock)} constructed with a
 * {@link Clock#fixed(Instant, java.time.ZoneId)} clock.
 *
 * <h3>Concurrency</h3>
 * This class is stateless once constructed (the {@link Clock} field is final
 * and {@link Clock} is thread-safe). No synchronization needed.
 */
@Component
@Primary
public class SystemClockService implements ClockService {

    private final Clock clock;

    /**
     * Production constructor — uses the system UTC clock.
     * Spring will use this when no other {@code Clock} bean is present.
     */
    public SystemClockService() {
        this.clock = Clock.systemUTC();
    }

    /**
     * Test-friendly constructor.  Supply a {@link Clock#fixed(Instant, java.time.ZoneId)}
     * to freeze time at a known instant.
     *
     * <pre>{@code
     *   Clock fixed = Clock.fixed(Instant.parse("2025-06-01T12:00:00Z"), ZoneOffset.UTC);
     *   ClockService cs = new SystemClockService(fixed);
     *   assertEquals(Instant.parse("2025-06-01T12:00:00Z"), cs.now());
     * }</pre>
     */
    public SystemClockService(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Instant now() {
        return Instant.now(clock);
    }

    @Override
    public LocalDate todayUtc() {
        return LocalDate.now(clock);
    }

    @Override
    public LocalDateTime nowUtc() {
        return LocalDateTime.now(clock);
    }

    @Override
    public Clock rawClock() {
        return clock;
    }
}
